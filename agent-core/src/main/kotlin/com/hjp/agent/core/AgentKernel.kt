package com.hjp.agent.core

import com.hjp.agent.contract.requestsAgentAction
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.agent.contract.TurnRoutePlan
import com.hjp.tool.contract.CatalogContext
import com.hjp.tool.contract.StandardToolErrorCodes
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolError
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRegistry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class AgentTurnPolicy(
    val maxToolCalls: Int = 3,
    val maxProtocolCorrections: Int = 1,
    val rejectMultipleCallsPerDecision: Boolean = true,
    val rejectRepeatedCall: Boolean = true,
    /**
     * How many times a turn may ask the model to finish a workflow it ended in prose.
     *
     * Bounded on purpose: a model that narrates instead of calling the terminal tool usually does it
     * again, and an unbounded retry turns one confused turn into a loop. One repair is enough to
     * recover the common case; after that the turn ends as unfinished rather than pretending.
     */
    val maxWorkflowRepairs: Int = 1,
    val historyStrategy: ConversationHistoryStrategy = ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP,
)

interface AgentRuntimeEnvironment {
    val localeTag: String
    val timeZoneId: String
    suspend fun grantedPermissions(): Set<String>
    suspend fun deviceCapabilities(): Set<String>
    suspend fun toolContext(sessionId: String, turnId: String): ToolExecutionContext
}

class AgentKernel(
    private val registry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val policyEngine: ToolPolicyEngine,
    private val sessionManager: AgentSessionManager,
    private val observationMapper: ToolObservationMapper,
    private val environment: AgentRuntimeEnvironment,
    private val turnPolicy: AgentTurnPolicy = AgentTurnPolicy(),
    private val workflowPolicy: AgentWorkflowPolicy = ProductionAgentWorkflowPolicy(),
    private val contextSelector: ModelContextSelector = ModelContextSelector(),
    /**
     * Verifies that the fixed prompt cost fits the deployed artifact's budget before any inference.
     * Without it an over-budget catalog is discovered only as garbled native output.
     */
    private val contextPreflight: (com.hjp.tool.contract.ToolCatalogSnapshot) -> ContextPreflightResult =
        { ContextPreflightResult.Ok(Int.MAX_VALUE) },
    /**
     * Mirrors what the native conversation accumulates. Rotation decisions use the whole native
     * input, not just the block being sent now.
     */
    val nativeLedger: NativeContextLedger = NativeContextLedger(),
    /** Debug-only, non-PII record of the last turn. Never surfaced in the release UI. */
    val diagnostics: AgentDiagnosticsRecorder = AgentDiagnosticsRecorder(),
) : AgentTurnEngine {
    override val mode = AgentKernelMode.REACT

    private val turnMutex = Mutex()
    private var nativeConversationTurns = 0
    private var nativeConversationTokens = 0

    override suspend fun resetSession(): Long {
        nativeConversationTurns = 0
        nativeConversationTokens = 0
        nativeLedger.clear()
        diagnostics.clear()
        return sessionManager.reset()
    }

    override fun close() = sessionManager.close()

    override fun runTurn(userText: String): Flow<AgentEvent> = flow {
        val normalized = userText.trim()
        if (normalized.isEmpty()) {
            emit(AgentEvent.UserError("메시지를 입력해 주세요."))
            return@flow
        }
        turnMutex.withLock {
            val turnId = UUID.randomUUID().toString()
            emit(AgentEvent.TurnStarted(turnId))
            val session = sessionManager.getOrCreate()
            val generation = session.generation
            val permissions = environment.grantedPermissions()
            val snapshot = registry.snapshot(CatalogContext(
                session.sessionId,
                environment.localeTag,
                permissions,
                environment.deviceCapabilities(),
            ))
            // Classified before the turn is recorded, by the same router that routes it, so the
            // action ledger holds requests rather than every sentence the user typed. The catalog is
            // needed first because the classification is made against the tools actually available.
            val requestsAction = DeterministicTurnRouter
                .act(session.turnContext(normalized, snapshot.contractsByModelName.keys))
                .requestsAgentAction &&
                // A sentence that quotes, recalls, negates or hypothesises about an action is about
                // an action rather than asking for one. The same rule already vetoes the capability
                // check; reusing it keeps one authority instead of two.
                !ReportedSpeechIntent.isAboutAnActionRatherThanARequest(normalized)
            sessionManager.beginTurn(turnId, normalized, requestsAction)

            // Rule-based pre-pass. It runs before the model so an ungrounded reference can never
            // become a guessed recipient, and so a question about the conversation never becomes a
            // contact search.
            val route = DeterministicTurnRouter.route(
                session.turnContext(normalized, snapshot.contractsByModelName.keys),
            )
            when (route) {
                is TurnRoutePlan.AnswerFromHistory -> {
                    finishWithoutTools(
                        route.messageKo, turnId, normalized, TurnOutcome.COMPLETED, generation,
                        ::emit, outcomeType = TurnOutcomeType.ANSWER_FROM_HISTORY,
                    )
                    return@withLock
                }
                is TurnRoutePlan.GeneralInformation -> {
                    finishWithoutTools(
                        route.messageKo, turnId, normalized, TurnOutcome.COMPLETED, generation,
                        ::emit, outcomeType = TurnOutcomeType.GENERAL_INFORMATION,
                    )
                    return@withLock
                }
                is TurnRoutePlan.Clarify -> {
                    finishWithoutTools(
                        route.questionKo, turnId, normalized, TurnOutcome.NEEDS_CLARIFICATION,
                        generation, ::emit, detailKo = route.reason.name,
                        outcomeType = TurnOutcomeType.CLARIFICATION_REQUIRED,
                    )
                    return@withLock
                }
                is TurnRoutePlan.Unsupported -> {
                    finishWithoutTools(
                        route.messageKo, turnId, normalized, TurnOutcome.COMPLETED, generation,
                        ::emit, outcomeType = TurnOutcomeType.UNSUPPORTED,
                    )
                    return@withLock
                }
                else -> Unit
            }
            // A correction retires the old target before anything else in the turn runs, so neither
            // the trusted-provenance seed below nor a later reference can still reach that person.
            if (route is TurnRoutePlan.CorrectionReplacement && isCurrent(generation)) {
                sessionManager.rejectContactsNamed(route.rejectedTerm)
            }
            recordDiagnostics(turnId, generation, route, emptyList(), emptyList(), 0, null, false)
            val groundedCardId = (route as? TurnRoutePlan.GroundedContact)?.cardId
            val modelText = when (route) {
                is TurnRoutePlan.Continue -> route.text
                is TurnRoutePlan.GroundedContact -> route.text
                is TurnRoutePlan.CorrectionReplacement -> route.text
                else -> normalized
            }

            // "회사가 어디야?" about an already-verified person is a read, not a plan. Answering it
            // from a fresh card read keeps the value grounded and current, and makes the behaviour
            // identical whatever model is deployed. No model inference and no side effect run here.
            if (route is TurnRoutePlan.ContactDetail) {
                answerContactDetail(route, snapshot, session, turnId, normalized, generation, ::emit)
                return@withLock
            }

            when (val preflight = contextPreflight(snapshot)) {
                is ContextPreflightResult.Failure -> {
                    failTurn(preflight.reasonKo, turnId, normalized, generation, ::emit)
                    return@withLock
                }
                is ContextPreflightResult.Ok -> Unit
            }

            val model = try {
                sessionManager.requireModelSession(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failTurn("온디바이스 모델을 시작하지 못했습니다.", turnId, normalized, generation, ::emit)
                return@withLock
            }
            val toolContext = environment.toolContext(session.sessionId, turnId)
            val lease = TurnLease(generation) { sessionManager.getOrCreate().generation }
            val sideEffects = SideEffectGuard()
            val observations = mutableListOf<ModelToolResponse>()
            val executedTools = mutableListOf<String>()
            val fingerprints = mutableSetOf<String>()
            var callCount = 0
            var protocolCorrections = 0
            val workflow = workflowPolicy.startTurn(modelText, environment.timeZoneId)
            // Session focus is not the same thing as this turn's target. A card the session has
            // verified stays in memory so a later "그 사람에게 메일 써줘" still works, but it becomes
            // *this* turn's contact only when this turn actually refers to somebody — see
            // [TurnContactTargetResolver] for the three kinds of evidence that count. Seeding the
            // provenance unconditionally made every later request contact-bound, so an ordinary
            // "2027년 2월 18일 오후 3시 교육 협의 일정 만들어줘" was validated as an event for whoever
            // had last been looked up and died asking for a contact lookup nobody had requested.
            val turnTarget = TurnContactTargetResolver.resolve(
                route, normalized, session.conversationMemory,
            )
            // Fail closed. If this turn names somebody the session has not surfaced, the person
            // currently in focus stops being an actionable target the moment the turn begins — not
            // only if the lookup fails. Falling back to them is how a mail addressed to the previous
            // person got sent in the held-out v3 run.
            val pendingNewTarget = TurnContactTargetResolver.namesSomeoneOtherThanFocus(
                normalized, session.conversationMemory,
            )
            val staleFocusCardId = session.conversationMemory.selectedContact
                ?.cardId
                ?.takeIf { pendingNewTarget && turnTarget is TurnContactTargetResolver.Target.None }
            (turnTarget as? TurnContactTargetResolver.Target.Confirmed)
                ?.let { workflow.seedTrustedContactProvenance(it.cardId) }
            // Retire it before the model runs, so no path in this turn or the next can reach it
            // without a fresh verification of whoever the user actually named.
            if (staleFocusCardId != null && isCurrent(generation)) {
                sessionManager.retireActionableFocus()
            }

            nativeLedger.setFixedContext(
                sessionManager.systemInstructionText,
                ContextPreflight.toolCatalogText(snapshot.contractsByModelName.values),
            )
            val promptContext = contextSelector.select(ContextRequest(
                transcript = session.transcript.toList(),
                memory = session.conversationMemory,
                currentInput = modelText,
                currentTurnId = turnId,
                capabilityContext = buildCapabilityContext(session),
                strategy = turnPolicy.historyStrategy,
                nativeConversationTurns = nativeConversationTurns,
                // Rotation is judged on the whole accumulated native input, not this block alone.
                nativeConversationTokens = nativeLedger.totalTokens(),
                // The real fixed cost, so a bootstrap is sized against the prompt that will actually
                // be sent rather than against an assumed catalog reserve.
                fixedContextTokens = nativeLedger.fixedTokens(),
            ))
            if (promptContext.startNewNativeConversation) {
                model.resetConversation()
                nativeLedger.rotate()
                nativeConversationTurns = 0
                nativeConversationTokens = 0
            }
            nativeConversationTurns += 1
            nativeConversationTokens += promptContext.estimatedTokens
            nativeLedger.appendUser(promptContext.render(modelText))
            recordDiagnostics(
                turnId, generation, route, emptyList(),
                promptContext.sections.map { it.name }, promptContext.estimatedTokens, null, false,
            )

            // Bounded repair state. `lastToolCallId` is the channel a continuation is sent on: a
            // repair only makes sense once a tool has actually run, which is exactly the case the
            // gap covers (a lookup succeeded and the terminal action never followed).
            var workflowRepairs = 0
            var lastToolCallId: String? = null
            var lastToolName: String? = null

            var decision = model.decide(ModelInput.User(
                text = modelText,
                safeCapabilityContext = buildCapabilityContext(session),
                promptContext = promptContext,
                turnContext = session.turnContext(
                    modelText, snapshot.contractsByModelName.keys, groundedCardId,
                ),
            ))
            // A native inference cannot be interrupted, so the first thing to check when it returns
            // is whether the session it belongs to still exists.
            if (lease.isStale()) return@withLock

            while (true) {
                currentCoroutineContext().ensureActive()
                if (lease.isStale()) return@withLock
                when (val current = decision) {
                    is ModelDecision.FinalCandidate -> {
                        if (lease.isStale()) return@withLock
                        // The model ended in prose while the workflow still owes a terminal tool.
                        // Accepting this is how "search → get_contact → (prose)" was recorded as a
                        // completed request that never opened anything. Give it one bounded chance
                        // to call the tool, through the ordinary tool-result channel.
                        val pendingTool = workflow.pendingTerminalTool()
                        if (pendingTool != null &&
                            workflowRepairs < turnPolicy.maxWorkflowRepairs &&
                            callCount < turnPolicy.maxToolCalls &&
                            lastToolCallId != null
                        ) {
                            workflowRepairs += 1
                            val note = workflow.continuationPrompt(
                                pendingTool, lastToolCallId!!, lastToolName!!,
                            )
                            // Sent on its own channel rather than as a tool result. A repair follows
                            // prose, so there is no pending tool call to answer, and emitting a
                            // tool-role message for a call the conversation already completed is not
                            // an ordering the native protocol defines.
                            decision = model.continueWithWorkflowNote(note)
                            continue
                        }
                        // Decided from workflow state, not from the boundary's wording or from a
                        // flag it may or may not have set. An actual model that asks "수신자를 알려
                        // 주세요" in prose reaches this line with clarification == null, and the turn
                        // still has to be typed as a clarification and answer with the missing slot.
                        val missingSlot = workflow.missingRequiredSlot()
                        val validated = when (val finalValidation = workflow.validateFinal(current.draftText)) {
                            WorkflowFinalValidationResult.Allow -> current.draftText
                            is WorkflowFinalValidationResult.Replace -> finalValidation.safeMessageKo
                        }
                        // When the boundary already produced a slot-specific question — the local
                        // gateway marks its own — that wording is kept, because it is more specific
                        // than a generic one ("이 명함에는 이메일 주소가 없습니다" beats "누구에게
                        // 보낼지 알려 주세요"). A boundary that returned bare prose gets the app's
                        // grounded question substituted, so an actual model cannot leave the user
                        // without the thing that is missing.
                        val safeDraft = if (current.clarification == null && missingSlot != null) {
                            missingSlot.questionKo
                        } else {
                            validated
                        }
                        val assembled = StringBuilder()
                        model.streamFinal(FinalAnswerInput(safeDraft, observations)).collect { token ->
                            assembled.append(token)
                            emit(AgentEvent.Token(token))
                        }
                        if (assembled.isEmpty() && safeDraft.isNotBlank()) {
                            assembled.append(safeDraft)
                            emit(AgentEvent.Token(safeDraft))
                        }
                        val finalText = assembled.toString()
                        nativeLedger.appendAssistant(finalText)
                        // A turn whose tool broke is a failed turn even when the wording is calm.
                        // Recording it as COMPLETED left a later "왜 실패했어?" with no failure to
                        // explain, so the agent answered that nothing had failed.
                        val brokeATool = workflow.hasTerminalExecutionFailure
                        // A boundary that answered because a required slot was missing did not complete the
                        // turn — it asked. Typing that as COMPLETED made "asked the user for the missing time"
                        // and "gave a general reply and ran nothing" the same recorded outcome, which is how a
                        // turn that did nothing scored as a success. This is the v4 contract; v1–v3 froze the
                        // older typing and are read through an explicit legacy translation instead.
                        val status = when {
                            brokeATool -> TurnOutcome.FAILED
                            missingSlot != null || current.clarification != null ->
                                TurnOutcome.NEEDS_CLARIFICATION
                            else -> TurnOutcome.COMPLETED
                        }
                        val outcomeType = TurnOutcomeClassifier.classify(
                            route, executedTools, status, brokeATool,
                        )
                        recordDiagnostics(
                            turnId, generation, route, executedTools,
                            promptContext.sections.map { it.name }, promptContext.estimatedTokens,
                            status.name, false,
                        )
                        if (isCurrent(generation)) {
                            sessionManager.completeTurn(
                                turnId, finalText, status, executedTools,
                                workflow.failureDetailKo, outcomeType,
                            )
                        }
                        emit(AgentEvent.FinalMessage(finalText))
                        return@withLock
                    }
                    is ModelDecision.Invalid -> {
                        failTurn(
                            current.safeReason.ifBlank { "요청을 안전하게 해석하지 못했습니다." },
                            turnId, normalized, generation, ::emit, executedTools,
                        )
                        return@withLock
                    }
                    is ModelDecision.ToolCalls -> {
                        if (turnPolicy.rejectMultipleCallsPerDecision && current.calls.size != 1) {
                            failTurn(
                                "한 번에 하나의 도구만 실행할 수 있습니다.", turnId, normalized, generation, ::emit,
                                executedTools, StandardToolErrorCodes.MULTIPLE_TOOL_CALLS_NOT_ALLOWED,
                            )
                            return@withLock
                        }
                        val rawCall = current.calls.singleOrNull()
                        if (rawCall == null) {
                            failTurn("모델이 빈 도구 호출을 반환했습니다.", turnId, normalized, generation, ::emit, executedTools)
                            return@withLock
                        }
                        if (callCount >= turnPolicy.maxToolCalls) {
                            val rejection = WorkflowRejection(
                                WorkflowRejectReason.TOOL_CALL_LIMIT_REACHED,
                                "한 요청에서 실행할 수 있는 도구 횟수를 초과했습니다.",
                            )
                            if (protocolCorrections >= turnPolicy.maxProtocolCorrections) {
                                failTurn(
                                    rejection.messageKo, turnId, normalized, generation, ::emit,
                                    executedTools, StandardToolErrorCodes.TOOL_CALL_LIMIT_REACHED,
                                )
                                return@withLock
                            }
                            protocolCorrections += 1
                            val response = workflow.rejectionResponse(rawCall, rejection)
                            observations += response
                            emit(AgentEvent.ToolFinished(rejection.messageKo))
                            decision = model.continueWithToolResult(response)
                            continue
                        }
                        val contract = snapshot.contractsByModelName[rawCall.modelToolName]
                        if (contract == null) {
                            val rejection = WorkflowRejection(
                                WorkflowRejectReason.TOOL_NOT_ALLOWED,
                                "현재 제공하지 않는 기능입니다.",
                            )
                            if (protocolCorrections >= turnPolicy.maxProtocolCorrections) {
                                failTurn(rejection.messageKo, turnId, normalized, generation, ::emit, executedTools)
                                return@withLock
                            }
                            protocolCorrections += 1
                            callCount += 1
                            val response = workflow.rejectionResponse(rawCall, rejection)
                            observations += response
                            emit(AgentEvent.ToolFinished(rejection.messageKo))
                            decision = model.continueWithToolResult(response)
                            continue
                        }
                        // Canonicalise before anything reads the arguments, so the fingerprint, the
                        // validator and the executor all see one form. The real model writes
                        // "2027-05-06T16:00:00" for a contract that says minute precision; treating
                        // that as a different request from "2027-05-06T16:00" cost three otherwise
                        // correct calendar turns in the desktop Gemma run.
                        val call = workflow.normalizeArguments(rawCall, contract)
                        val fingerprint = call.modelToolName + ":" +
                            JsonCanonicalizer.sha256(JsonCanonicalizer.canonical(call.arguments))
                        if (turnPolicy.rejectRepeatedCall && fingerprint in fingerprints) {
                            val rejection = WorkflowRejection(
                                WorkflowRejectReason.REPEATED_TOOL_CALL,
                                "동일한 도구 호출이 반복되었습니다.",
                            )
                            if (protocolCorrections >= turnPolicy.maxProtocolCorrections) {
                                failTurn(
                                    "동일한 오류가 반복되어 안전하게 중단했습니다.", turnId, normalized, generation,
                                    ::emit, executedTools, StandardToolErrorCodes.REPEATED_TOOL_CALL,
                                )
                                return@withLock
                            }
                            protocolCorrections += 1
                            callCount += 1
                            val response = workflow.rejectionResponse(call, rejection)
                            observations += response
                            emit(AgentEvent.ToolFinished(rejection.messageKo))
                            decision = model.continueWithToolResult(response)
                            continue
                        }
                        callCount += 1
                        when (val validation = workflow.validate(call, contract)) {
                            WorkflowValidationResult.Allow -> Unit
                            is WorkflowValidationResult.Reject -> {
                                if (protocolCorrections >= turnPolicy.maxProtocolCorrections) {
                                    failTurn(
                                        "같은 요청을 안전하게 수정하지 못했습니다. ${validation.rejection.messageKo}",
                                        turnId, normalized, generation, ::emit, executedTools,
                                    )
                                    return@withLock
                                }
                                protocolCorrections += 1
                                val response = workflow.rejectionResponse(call, validation.rejection)
                                observations += response
                                emit(AgentEvent.ToolFinished(validation.rejection.messageKo))
                                decision = model.continueWithToolResult(response)
                                continue
                            }
                        }
                        fingerprints += fingerprint
                        // One irreversible action per turn. A retry or a duplicated model call must
                        // not open a second compose screen or write a card twice.
                        if (!sideEffects.tryReserve(contract)) {
                            val rejection = WorkflowRejection(
                                WorkflowRejectReason.REPEATED_TOOL_CALL,
                                "같은 요청에서 외부 작업을 두 번 실행할 수 없습니다.",
                            )
                            val response = workflow.rejectionResponse(call, rejection)
                            observations += response
                            emit(AgentEvent.ToolFinished(rejection.messageKo))
                            decision = model.continueWithToolResult(response)
                            if (lease.isStale()) return@withLock
                            continue
                        }
                        if (lease.isStale()) return@withLock
                        val policy = policyEngine.evaluate(
                            contract,
                            call,
                            AgentSessionView(session.sessionId, permissions),
                        )
                        val policyFailure = when (policy) {
                            ToolPolicyDecision.Allow -> null
                            is ToolPolicyDecision.RequirePermission -> {
                                emit(AgentEvent.PermissionRequested(policy.permissions))
                                ToolError(StandardToolErrorCodes.PERMISSION_REQUIRED,
                                    "필요한 권한이 없습니다.", true)
                            }
                            is ToolPolicyDecision.RequireConfirmation -> {
                                emit(AgentEvent.ConfirmationRequested(policy.promptKo))
                                if (toolContext.confirmationGateway.confirm(policy.promptKo)) null
                                else ToolError(StandardToolErrorCodes.CONFIRMATION_REJECTED,
                                    "사용자가 작업을 취소했습니다.", false)
                            }
                            is ToolPolicyDecision.Deny -> policy.error
                        }
                        // Last gate before an irreversible action.
                        if (lease.isStale()) return@withLock
                        val result = if (policyFailure != null) {
                            sideEffects.release(contract)
                            policyFailureResult(call, contract, policyFailure)
                        } else {
                            emit(AgentEvent.ToolStarted(contract.presentation.runningMessageKo))
                            toolExecutor.execute(call, snapshot, toolContext)
                        }
                        if (lease.isStale()) return@withLock
                        if (result is ToolExecutionResult.Success) {
                            if (isCurrent(generation)) {
                                sessionManager.apply(result.sessionUpdates)
                                sessionManager.projectToolResult(contract.modelName, result.data, turnId)
                            }
                            executedTools += contract.modelName
                        }
                        if (result is ToolExecutionResult.Failure &&
                            contract.modelName == ToolResultProjector.GET
                        ) {
                            // The remembered card no longer resolves; dropping the focus stops a
                            // later turn from acting on a person the store cannot confirm.
                            call.arguments["card_id"]?.let { value ->
                                val staleId = value.toString().trim('"')
                                if (isCurrent(generation)) sessionManager.invalidateStaleCard(staleId)
                            }
                        }
                        workflow.recordResult(call, result)
                        // Remember the channel a repair would be sent on.
                        lastToolCallId = call.callId
                        lastToolName = call.modelToolName
                        val observation = observationMapper.toModelResponse(result, contract)
                        observations += observation.modelResponse
                        nativeLedger.appendToolCall(call.modelToolName, call.arguments.toString())
                        nativeLedger.appendToolResponse(
                            observation.modelResponse.modelToolName,
                            observation.modelResponse.payload.toString(),
                        )
                        emit(AgentEvent.ToolFinished(observation.safeUiMessageKo))
                        decision = model.continueWithToolResult(observation.modelResponse)
                        if (lease.isStale()) return@withLock
                    }
                }
            }
        }
    }

    /**
     * Reads one verified card and answers from its current values.
     *
     * The card id comes from the router, which only ever hands over an id this session obtained from
     * a tool, so this cannot widen into a lookup of an arbitrary contact. A lookup failure retires
     * the focus instead of answering from the stale projection.
     */
    private suspend fun answerContactDetail(
        route: TurnRoutePlan.ContactDetail,
        snapshot: com.hjp.tool.contract.ToolCatalogSnapshot,
        session: AgentSession,
        turnId: String,
        userText: String,
        generation: Long,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        val contract = snapshot.contractsByModelName[AgentWorkflowSession.GET_CONTACT]
        if (contract == null) {
            failTurn("명함 상세 조회 기능을 사용할 수 없습니다.", turnId, userText, generation, emit)
            return
        }
        val lease = TurnLease(generation) { sessionManager.getOrCreate().generation }
        val call = ModelToolCall(
            callId = UUID.randomUUID().toString(),
            modelToolName = AgentWorkflowSession.GET_CONTACT,
            arguments = buildJsonObject {
                put("card_id", route.cardId)
                put("purpose", "display")
            },
        )
        emit(AgentEvent.ToolStarted(contract.presentation.runningMessageKo))
        val result = toolExecutor.execute(call, snapshot, environment.toolContext(session.sessionId, turnId))
        if (lease.isStale()) return
        if (result !is ToolExecutionResult.Success) {
            if (isCurrent(generation)) sessionManager.invalidateStaleCard(route.cardId)
            val reason = (result as? ToolExecutionResult.Failure)?.error?.safeMessageKo
                ?.takeIf(String::isNotBlank)
                ?: "명함 상세정보를 확인하지 못했습니다."
            emit(AgentEvent.ToolFinished(reason))
            failTurn(reason, turnId, userText, generation, emit, listOf(contract.modelName))
            return
        }
        if (isCurrent(generation)) {
            sessionManager.apply(result.sessionUpdates)
            sessionManager.projectToolResult(contract.modelName, result.data, turnId)
        }
        emit(AgentEvent.ToolFinished(contract.presentation.runningMessageKo))
        val message = ContactDetailAnswer.render(route, result.data)
        if (isCurrent(generation)) {
            sessionManager.completeTurn(
                turnId, message, TurnOutcome.COMPLETED, listOf(contract.modelName),
                outcomeType = TurnOutcomeType.CONTACT_DETAIL_SHOWN,
            )
        }
        emit(AgentEvent.Token(message))
        emit(AgentEvent.FinalMessage(message))
    }

    private fun recordDiagnostics(
        turnId: String,
        generation: Long,
        route: TurnRoutePlan,
        executedTools: List<String>,
        sections: List<String>,
        promptTokens: Int,
        outcome: String?,
        staleAborted: Boolean,
    ) {
        diagnostics.record(TurnDiagnostics(
            kernelMode = mode,
            sessionGeneration = generation,
            turnId = turnId,
            routePlan = route::class.simpleName ?: "Unknown",
            evidence = when (route) {
                is TurnRoutePlan.GroundedContact -> "TOOL_VERIFIED_CARD"
                is TurnRoutePlan.Clarify -> route.reason.name
                is TurnRoutePlan.AnswerFromHistory -> "SESSION_TRANSCRIPT"
                is TurnRoutePlan.Unsupported -> "CAPABILITY_VETO"
                else -> "USER_INPUT_ONLY"
            },
            outcome = outcome,
            executedTools = executedTools.toList(),
            promptSections = sections,
            promptTokensEstimated = promptTokens,
            nativeContext = nativeLedger.snapshot(),
            staleAborted = staleAborted,
        ))
    }

    private suspend fun isCurrent(generation: Long): Boolean =
        sessionManager.getOrCreate().generation == generation

    private suspend fun finishWithoutTools(
        message: String,
        turnId: String,
        userText: String,
        outcome: TurnOutcome,
        generation: Long,
        emit: suspend (AgentEvent) -> Unit,
        detailKo: String? = null,
        outcomeType: TurnOutcomeType? = null,
    ) {
        if (isCurrent(generation)) {
            sessionManager.completeTurn(turnId, message, outcome, emptyList(), detailKo, outcomeType)
        }
        emit(AgentEvent.Token(message))
        emit(AgentEvent.FinalMessage(message))
    }

    private suspend fun failTurn(
        message: String,
        turnId: String,
        userText: String,
        generation: Long,
        emit: suspend (AgentEvent) -> Unit,
        executedTools: List<String> = emptyList(),
        code: com.hjp.tool.contract.ToolErrorCode? = null,
    ) {
        if (isCurrent(generation)) {
            sessionManager.completeTurn(
                turnId, message, TurnOutcome.FAILED, executedTools, message, TurnOutcomeType.FAILED,
            )
        }
        emit(AgentEvent.UserError(message, code))
    }

    private fun buildCapabilityContext(session: AgentSession) = buildJsonObject {
        val now = System.currentTimeMillis()
        session.capabilityState.entries
            .filter { (_, state) -> state.expiresAtEpochMillis?.let { it > now } ?: true }
            .groupBy { it.key.namespace }
            .forEach { (namespace, entries) ->
                putJsonObject(namespace) {
                    entries.forEach { (key, state) -> put(key.key, state.value) }
                }
            }
    }

    private fun policyFailureResult(
        call: ModelToolCall,
        contract: ToolContract,
        error: ToolError,
    ) = ToolExecutionResult.Failure(
        call.callId,
        contract.capabilityId,
        contract.version,
        0,
        error,
    )
}
