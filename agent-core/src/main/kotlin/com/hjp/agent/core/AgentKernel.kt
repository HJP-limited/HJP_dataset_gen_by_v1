package com.hjp.agent.core

import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
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
) {
    private val turnMutex = Mutex()

    fun runTurn(userText: String): Flow<AgentEvent> = flow {
        val normalized = userText.trim()
        if (normalized.isEmpty()) {
            emit(AgentEvent.UserError("메시지를 입력해 주세요."))
            return@flow
        }
        turnMutex.withLock {
            val turnId = UUID.randomUUID().toString()
            emit(AgentEvent.TurnStarted(turnId))
            val session = sessionManager.getOrCreate()
            val permissions = environment.grantedPermissions()
            val snapshot = registry.snapshot(CatalogContext(
                session.sessionId,
                environment.localeTag,
                permissions,
                environment.deviceCapabilities(),
            ))
            val model = try {
                sessionManager.requireModelSession(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emit(AgentEvent.UserError("온디바이스 모델을 시작하지 못했습니다."))
                return@withLock
            }
            val toolContext = environment.toolContext(session.sessionId, turnId)
            val observations = mutableListOf<ModelToolResponse>()
            val fingerprints = mutableSetOf<String>()
            var callCount = 0
            var decision = model.decide(ModelInput.User(normalized, buildCapabilityContext(session)))

            while (true) {
                currentCoroutineContext().ensureActive()
                when (val current = decision) {
                    is ModelDecision.FinalCandidate -> {
                        val assembled = StringBuilder()
                        model.streamFinal(FinalAnswerInput(current.draftText, observations)).collect { token ->
                            assembled.append(token)
                            emit(AgentEvent.Token(token))
                        }
                        if (assembled.isEmpty() && current.draftText.isNotBlank()) {
                            assembled.append(current.draftText)
                            emit(AgentEvent.Token(current.draftText))
                        }
                        emit(AgentEvent.FinalMessage(assembled.toString()))
                        return@withLock
                    }
                    is ModelDecision.Invalid -> {
                        emit(AgentEvent.UserError(current.safeReason.ifBlank {
                            "요청을 안전하게 해석하지 못했습니다."
                        }))
                        return@withLock
                    }
                    is ModelDecision.ToolCalls -> {
                        if (turnPolicy.rejectMultipleCallsPerDecision && current.calls.size != 1) {
                            emit(AgentEvent.UserError(
                                "한 번에 하나의 도구만 실행할 수 있습니다.",
                                StandardToolErrorCodes.MULTIPLE_TOOL_CALLS_NOT_ALLOWED,
                            ))
                            return@withLock
                        }
                        val call = current.calls.singleOrNull()
                        if (call == null) {
                            emit(AgentEvent.UserError("모델이 빈 도구 호출을 반환했습니다."))
                            return@withLock
                        }
                        if (callCount >= turnPolicy.maxToolCalls) {
                            emit(AgentEvent.UserError(
                                "한 요청에서 실행할 수 있는 도구 횟수를 초과했습니다.",
                                StandardToolErrorCodes.TOOL_CALL_LIMIT_REACHED,
                            ))
                            return@withLock
                        }
                        val contract = snapshot.contractsByModelName[call.modelToolName]
                        if (contract == null) {
                            emit(AgentEvent.UserError("현재 제공하지 않는 기능을 요청했습니다."))
                            return@withLock
                        }
                        val fingerprint = call.modelToolName + ":" +
                            JsonCanonicalizer.sha256(JsonCanonicalizer.canonical(call.arguments))
                        if (turnPolicy.rejectRepeatedCall && !fingerprints.add(fingerprint)) {
                            emit(AgentEvent.UserError(
                                "동일한 도구 호출이 반복되어 중단했습니다.",
                                StandardToolErrorCodes.REPEATED_TOOL_CALL,
                            ))
                            return@withLock
                        }
                        callCount += 1
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
                        val result = if (policyFailure != null) {
                            policyFailureResult(call, contract, policyFailure)
                        } else {
                            emit(AgentEvent.ToolStarted(contract.presentation.runningMessageKo))
                            toolExecutor.execute(call, snapshot, toolContext)
                        }
                        if (result is ToolExecutionResult.Success) {
                            sessionManager.apply(result.sessionUpdates)
                        }
                        val observation = observationMapper.toModelResponse(result, contract)
                        observations += observation.modelResponse
                        emit(AgentEvent.ToolFinished(observation.safeUiMessageKo))
                        decision = model.continueWithToolResult(observation.modelResponse)
                    }
                }
            }
        }
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
