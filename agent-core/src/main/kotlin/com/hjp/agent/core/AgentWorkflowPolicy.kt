package com.hjp.agent.core

import com.hjp.agent.contract.ModelWorkflowNote
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolExecutionResult
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

enum class WorkflowRejectReason {
    TOOL_NOT_ALLOWED,
    TOOL_NOT_REQUESTED,
    UNSUPPORTED_REQUEST,
    MISSING_REQUIRED_INFORMATION,
    INVALID_ARGUMENTS,
    INVALID_EMAIL,
    INVALID_PHONE,
    INVALID_DATETIME,
    CONTACT_LOOKUP_REQUIRED,
    CONTACT_NOT_FOUND,
    CONTACT_SELECTION_REQUIRED,
    CONTACT_DETAIL_REQUIRED,
    CONTACT_VALUE_NOT_VERIFIED,
    CURRENT_DATETIME_REQUIRED,
    UNNECESSARY_TOOL_CALL,
    WORKFLOW_ORDER_VIOLATION,
    REPEATED_TOOL_CALL,
    TOOL_CALL_LIMIT_REACHED,
}

data class WorkflowRejection(
    val reason: WorkflowRejectReason,
    val messageKo: String,
    val allowedNextTools: Set<String> = emptySet(),
)

sealed interface WorkflowValidationResult {
    data object Allow : WorkflowValidationResult
    data class Reject(val rejection: WorkflowRejection) : WorkflowValidationResult
}

sealed interface WorkflowFinalValidationResult {
    data object Allow : WorkflowFinalValidationResult
    data class Replace(
        val safeMessageKo: String,
        val reason: WorkflowRejectReason,
    ) : WorkflowFinalValidationResult
}

fun interface AgentWorkflowPolicy {
    fun startTurn(userText: String, deviceTimeZoneId: String): AgentWorkflowSession
}

class ProductionAgentWorkflowPolicy : AgentWorkflowPolicy {
    override fun startTurn(userText: String, deviceTimeZoneId: String) =
        AgentWorkflowSession(userText.trim())
}

/**
 * Per-turn state machine. It never creates a tool call; it only validates a
 * model-originated call and records trusted tool results.
 */
/**
 * A slot an action tool cannot run without.
 *
 * Typed rather than a string so the question the user sees is derived from the slot, and a new slot
 * cannot be added without deciding what to ask about it.
 */
enum class MissingSlot(val questionKo: String) {
    COMPOSE_RECIPIENT("누구에게 보낼지 알려 주세요. 이 대화에서 확인된 연락처가 없습니다."),
    CALENDAR_START_TIME("일정을 언제로 잡을지 날짜와 시간을 알려 주세요."),
    UPDATE_FIELD("명함에서 수정할 필드와 새 값을 알려 주세요."),
    UPDATE_TARGET("어떤 분의 명함을 수정할지 알려 주세요."),
}

class AgentWorkflowSession internal constructor(
    private val userText: String,
) {
    private val successfulTools = mutableSetOf<String>()
    private val failedTools = mutableSetOf<String>()
    private var searchResultIds: List<String>? = null
    private var selectedContact: JsonObject? = null
    private var currentDate: LocalDate? = null
    private var lastRejection: WorkflowRejection? = null
    private var terminalExecutionFailure = false
    private var trustedSessionContactTarget = false

    private val directEmails = EMAIL_REGEX.findAll(userText).map { it.value.lowercase() }.toSet()
    private val directPhones = PHONE_REGEX.findAll(userText).map { normalizePhone(it.value) }.toSet()
    private val previewOnly = PREVIEW_MARKERS.any(userText::contains) &&
        !EXTERNAL_UI_MARKERS.any(userText::contains)
    private val unsupportedRequest = UNSUPPORTED_REQUEST_MARKERS.any(userText::contains)
    private val explicitRealSend = REAL_SEND_MARKERS.any(userText::contains)
    private val invalidEmailLike = (
        INVALID_EMAIL_LIKE_REGEX.containsMatchIn(userText) && directEmails.isEmpty()
    ) || MALFORMED_SPACED_EMAIL_REGEX.containsMatchIn(userText)
    private val contactNameTarget = directEmails.isEmpty() && directPhones.isEmpty() &&
        (NAME_RECIPIENT_REGEX.containsMatchIn(userText) || NAME_CARD_REGEX.containsMatchIn(userText))
    /**
     * The request with person-name spans blanked, for keyword tests only.
     *
     * Asked of the raw sentence, "does this mention 일정?" cannot tell a scheduling request from a
     * contact named 김일정, and "does this mention 문자?" cannot tell an SMS from 문자현. Everything
     * that needs the real words — the recipient, the body, the dates — keeps reading [userText].
     */
    private val maskedUserText = PersonNameMask.maskNames(userText)

    private val calendarIntent = CALENDAR_MARKERS.any(maskedUserText::contains)
    private val composeIntent = COMPOSE_MARKERS.any(maskedUserText::contains)
    /**
     * A card edit is identified by the verb plus what it acts on. Requiring the literal word 명함
     * missed every turn where the reference had already been resolved — "김지원 메모를 VIP로 수정해줘"
     * is the same request as "김지원 명함 수정해줘", and rejecting it as "not an update" made a
     * correct model call unexecutable.
     */
    private val updateIntent = CardUpdateIntent.hasUpdateVerb(userText) &&
        (CARD_OBJECT_MARKERS.any(userText::contains) || CARD_FIELD_MARKERS.any(userText::contains))
    /** An edit can only run once the field *and* its new value are both on the table. */
    private val updateSlotsComplete = CARD_FIELD_MARKERS.any(userText::contains) &&
        UPDATE_VALUE_REGEX.containsMatchIn(userText)
    /**
     * Reading contacts is "a card object plus a read verb", not a fixed list of phrasings. The
     * literal-phrase list accepted "명함 찾아줘" but rejected "명함 검색해줘" and "연락처 조회해줘",
     * which made an ordinary search unexecutable depending on which synonym the user picked.
     */
    private val contactReadIntent = ContactReadIntent.isCardRead(userText)
    private val relativeDateIntent = calendarIntent && RELATIVE_DATE_MARKERS.any(userText::contains)
    /**
     * "지금은 언제인가" in any of its ordinary forms, and only when nothing more specific owns the
     * time expression. The rule lives in [CurrentDateTimeIntent] because the pre-router and the
     * emulator gateway have to reach the same verdict; three private copies of it disagreed on
     * "지금 몇 시인지 알려줘", so the turn was routed as a clock query and then validated as one that
     * had failed to consult the clock.
     */
    private val currentTimeIntent = CurrentDateTimeIntent.isDirectQuery(userText)
    /**
     * Whether the request states a time of day, decided by the shared temporal vocabulary.
     *
     * The private pattern this replaces recognised only 오전/오후 + digits + 시, so 14시, 2시, 정오,
     * 자정, 새벽 3시 and 14:30 all counted as "no time given" — and once a calendar request without a
     * time had to ask for one, those complete requests became questions.
     */
    private val calendarTimeProvided = TemporalSlotIntent.hasTime(userText)

    /** Whether the request states a day. A date is not a time; they are separate slots. */
    private val calendarDateProvided = TemporalSlotIntent.hasDate(userText)

    /** Seeds only a card ID previously emitted by search_contacts in this same agent session. */
    fun seedTrustedContactProvenance(cardId: String) {
        val value = cardId.trim()
        if (value.isNotEmpty()) {
            searchResultIds = listOf(value)
            trustedSessionContactTarget = true
        }
    }

    /**
     * The call as it will be executed, with any argument whose form is merely a notation difference
     * put into canonical form.
     *
     * Deliberately narrow: it never invents, completes or corrects a *value*. The only thing it does
     * is drop a zero seconds component from a datetime, which is a notation the contract already
     * describes. A value that would change meaning is left exactly as the model wrote it so that
     * [validate] can refuse it and say why.
     */
    fun normalizeArguments(call: ModelToolCall, contract: ToolContract): ModelToolCall {
        if (contract.modelName != CREATE_CALENDAR_EVENT) return call
        val rewritten = DATETIME_ARGUMENTS.mapNotNull { name ->
            val raw = call.arguments.string(name) ?: return@mapNotNull null
            val canonical = LocalDateTimeCanonicalizer.canonicalOrNull(raw) ?: return@mapNotNull null
            if (canonical == raw) null else name to canonical
        }
        if (rewritten.isEmpty()) return call
        val arguments = buildJsonObject {
            call.arguments.forEach { (key, value) ->
                val replacement = rewritten.firstOrNull { it.first == key }?.second
                if (replacement != null) put(key, replacement) else put(key, value)
            }
        }
        return call.copy(arguments = arguments)
    }

    fun validate(call: ModelToolCall, contract: ToolContract): WorkflowValidationResult {
        validateSchema(call.arguments, contract.inputSchema)?.let {
            return WorkflowValidationResult.Reject(it)
        }
        if (unsupportedRequest) {
            return reject(
                WorkflowRejectReason.UNSUPPORTED_REQUEST,
                "요청한 기능은 현재 지원하지 않습니다.",
            )
        }
        if (explicitRealSend) {
            return reject(
                WorkflowRejectReason.UNSUPPORTED_REQUEST,
                "이메일이나 문자를 직접 전송할 수 없습니다. 작성 화면만 열 수 있습니다.",
            )
        }
        if (previewOnly) {
            return reject(
                WorkflowRejectReason.TOOL_NOT_REQUESTED,
                "초안이나 예시만 요청되어 도구를 실행하지 않습니다.",
            )
        }
        if (invalidEmailLike && call.modelToolName in setOf(SEARCH_CONTACTS, GET_CONTACT, OPEN_COMPOSE)) {
            return reject(
                WorkflowRejectReason.INVALID_EMAIL,
                "입력한 이메일 주소 형식이 올바르지 않습니다. 정확한 주소를 확인해 주세요.",
            )
        }
        if (calendarIntent && !calendarTimeProvided &&
            call.modelToolName in setOf(GET_CURRENT_DATETIME, CREATE_CALENDAR_EVENT)
        ) {
            return reject(
                WorkflowRejectReason.MISSING_REQUIRED_INFORMATION,
                "일정을 만들려면 시작 시각이 필요합니다.",
            )
        }

        return when (call.modelToolName) {
            SEARCH_CONTACTS -> validateSearch()
            GET_CONTACT -> validateGetContact(call)
            OPEN_COMPOSE -> validateCompose(call)
            CREATE_CALENDAR_EVENT -> validateCalendar(call)
            UPDATE_BUSINESS_CARD -> validateUpdate(call)
            GET_CURRENT_DATETIME -> validateCurrentDatetime()
            // Registry membership is the allowlist. Domain-specific ordering
            // rules apply only to the production tools known here.
            else -> WorkflowValidationResult.Allow
        }
    }

    /**
     * True when a tool in this turn failed and nothing later recovered it.
     *
     * The kernel needs this to close the turn as FAILED: a turn that ends with a polite sentence but
     * a broken tool call is still a failure, and recording it as COMPLETED left "방금 그거 왜
     * 실패했어?" with nothing to explain.
     */
    val hasTerminalExecutionFailure: Boolean get() = terminalExecutionFailure

    /** User-level reason for [hasTerminalExecutionFailure]. Never a raw exception. */
    var failureDetailKo: String? = null
        private set

    fun recordResult(call: ModelToolCall, result: ToolExecutionResult) {
        if (result !is ToolExecutionResult.Success) {
            failedTools += call.modelToolName
            terminalExecutionFailure = true
            failureDetailKo = (result as? ToolExecutionResult.Failure)?.error?.safeMessageKo
                ?.takeIf(String::isNotBlank)
                ?: "‘${call.modelToolName}’ 실행에 실패했습니다."
            return
        }
        // A retry that succeeds recovers the earlier failure of that same tool.
        failedTools -= call.modelToolName
        if (failedTools.isEmpty()) {
            terminalExecutionFailure = false
            failureDetailKo = null
        }
        lastRejection = null
        successfulTools += call.modelToolName
        when (call.modelToolName) {
            SEARCH_CONTACTS -> {
                val results = result.data["results"] as? JsonArray
                searchResultIds = results.orEmpty().mapNotNull { item ->
                    ((item as? JsonObject)?.get("card_id") as? JsonPrimitive)
                        ?.content?.trim()?.takeIf(String::isNotEmpty)
                }
            }
            GET_CONTACT -> selectedContact = result.data
            GET_CURRENT_DATETIME -> {
                currentDate = result.data.string("date")?.let {
                    try {
                        LocalDate.parse(it)
                    } catch (_: DateTimeParseException) {
                        null
                    }
                }
            }
        }
    }

    fun rejectionResponse(
        call: ModelToolCall,
        rejection: WorkflowRejection,
    ): ModelToolResponse {
        lastRejection = rejection
        return ModelToolResponse(
            callId = call.callId,
            modelToolName = call.modelToolName,
            payload = buildJsonObject {
                put("status", "rejected")
                put("reason", rejection.reason.name)
                put("message", rejection.messageKo)
                putJsonArray("allowed_next_tools") {
                    rejection.allowedNextTools.sorted().forEach { add(JsonPrimitive(it)) }
                }
            },
        )
    }

    /**
     * Final text guard. It never invents or executes a tool call: it only
     * prevents an incomplete workflow or a false send-completion claim from
     * reaching the user.
     */
    fun validateFinal(draftText: String): WorkflowFinalValidationResult {
        lastRejection?.let {
            return WorkflowFinalValidationResult.Replace(it.messageKo, it.reason)
        }
        // A screen-opening tool ran, and the draft claims the work itself is done.
        SCREEN_ONLY_COMPLETION_CLAIMS.forEach { (tool, claims) ->
            if (tool in successfulTools && claims.any(draftText::contains)) {
                return WorkflowFinalValidationResult.Replace(
                    SCREEN_ONLY_CORRECTION.getValue(tool),
                    WorkflowRejectReason.WORKFLOW_ORDER_VIOLATION,
                )
            }
        }
        // Nothing ran, or the thing that would have done the work failed, and the draft still says
        // it happened. This is the claim that makes a broken turn read like a successful one.
        val didSomething = successfulTools.any { it in SIDE_EFFECTING_TOOLS }
        if ((!didSomething || terminalExecutionFailure) && COMPLETION_CLAIMS.any(draftText::contains)) {
            return WorkflowFinalValidationResult.Replace(
                failureDetailKo ?: "요청한 작업을 완료하지 못했습니다.",
                WorkflowRejectReason.WORKFLOW_ORDER_VIOLATION,
            )
        }
        if (terminalExecutionFailure || previewOnly || unsupportedRequest || explicitRealSend ||
            invalidEmailLike || (calendarIntent && !calendarTimeProvided)
        ) {
            return WorkflowFinalValidationResult.Allow
        }

        val unfinishedMessage = when {
            currentTimeIntent && GET_CURRENT_DATETIME !in successfulTools ->
                "현재 시각 조회를 완료하지 못했습니다. 다시 시도해 주세요."
            // Reading one already-verified card by id satisfies "show me the contact" just as well
            // as a fresh search. Demanding search_contacts here reported a completed lookup as a
            // failure whenever the turn resolved a reference instead of a name.
            contactReadIntent && SEARCH_CONTACTS !in successfulTools &&
                GET_CONTACT !in successfulTools ->
                "연락처 검색을 완료하지 못했습니다. 다시 시도해 주세요."
            // Asking which field to change is the correct end of an under-specified edit, not an
            // interrupted workflow, so it must not be overwritten with a retry message.
            updateIntent && updateSlotsComplete && UPDATE_BUSINESS_CARD !in successfulTools &&
                !contactLookupTerminal() ->
                "명함 수정 절차를 완료하지 못했습니다. 다시 시도해 주세요."
            calendarIntent && CREATE_CALENDAR_EVENT !in successfulTools &&
                !contactLookupTerminal() ->
                "일정 작성 절차를 완료하지 못했습니다. 다시 시도해 주세요."
            composeIntent && hasExplicitComposeExecution() &&
                OPEN_COMPOSE !in successfulTools && !contactLookupTerminal() &&
                hasResolvableRecipient() ->
                "작성 화면 열기 절차를 완료하지 못했습니다. 다시 시도해 주세요."
            else -> null
        }
        return if (unfinishedMessage == null) {
            WorkflowFinalValidationResult.Allow
        } else {
            WorkflowFinalValidationResult.Replace(
                unfinishedMessage,
                WorkflowRejectReason.WORKFLOW_ORDER_VIOLATION,
            )
        }
    }

    /**
     * The terminal tool this turn still owes, or null when nothing is outstanding.
     *
     * A turn that resolved a contact and then ended in prose has not done what the user asked; the
     * model simply stopped narrating. Naming the tool that is missing is what lets the kernel give
     * it one bounded chance to finish instead of either accepting the prose or failing the turn.
     *
     * It reports a tool only when acting would be *safe*: the request must be for that action, the
     * action must not already have run, and the parts the validator needs — a resolvable recipient,
     * a stated time — must be present. When they are not, the honest end of the turn is a question,
     * so this returns null and the existing clarification path handles it.
     */
    /**
     * A required slot this request does not supply, or null when nothing is missing.
     *
     * Read from the request and the workflow's own state — never from the model's wording and never
     * from a flag the model boundary happened to attach. The deterministic local gateway marks its
     * missing-slot answers; the LiteRT gateway cannot, because an actual model just writes a sentence.
     * Deciding here means both boundaries produce the same typed outcome for the same request, which
     * is the difference between "the app asked for the missing time" and "the app said something
     * friendly and did nothing".
     *
     * Returns the slot, so the turn can ask about the thing that is actually absent.
     */
    fun missingRequiredSlot(): MissingSlot? = when {
        // Anything that already acted, failed, or was refused is not a missing-slot turn.
        terminalExecutionFailure || previewOnly || unsupportedRequest || explicitRealSend ->
            null
        successfulTools.any { it in SIDE_EFFECTING_TOOLS } -> null

        // A schedule with no date or time in it, and no way to derive one.
        //
        // Not a schedule request when the turn is a compose request: the calendar markers are matched
        // against the whole utterance, and a mail whose *subject* is "회의 안내" with a body about
        // 일정 공유 contains them without asking for a meeting. Reading that as a schedule missing its
        // time turned three ordinary compose turns into questions.
        // A date is not a time. "내일 점검 일정 만들어줘" and "다음 주에 회의 잡아줘" name a day and
        // nothing else, and create_calendar_event needs a start *time* — the earlier rule exempted
        // them because a relative-date marker was present, so they passed as complete requests.
        calendarIntent && !composeIntent && !calendarTimeProvided &&
            CREATE_CALENDAR_EVENT !in successfulTools -> MissingSlot.CALENDAR_START_TIME

        // A message with nobody to send it to. A direct address or number counts as a recipient, so
        // "test@example.com으로 보내줘" is not treated as missing anything.
        composeIntent && hasExplicitComposeExecution() && !hasResolvableRecipient() ->
            MissingSlot.COMPOSE_RECIPIENT

        // An edit that never says what to change.
        //
        // Deliberately not here: an ambiguous name, a contact with no address on file, and a lookup
        // that found nobody. Those are not missing slots — the request said who — and each already
        // has a more specific answer and its own typed outcome. Folding them in reclassified
        // ambiguity as a missing slot and replaced "이 명함에는 이메일 주소가 없습니다" with a
        // vaguer question.
        updateIntent && !updateSlotsComplete && UPDATE_BUSINESS_CARD !in successfulTools ->
            MissingSlot.UPDATE_FIELD

        // The field and the value are both stated and there is still nobody to edit: "메모를
        // 우선연락으로 수정해줘" with no name in the sentence and no verified contact in the session.
        // MissingSlot.UPDATE_TARGET existed for this and was never returned, so the turn fell through
        // to whatever the model happened to say.
        //
        // Narrow on purpose: it requires the slots to be complete, so it cannot collide with
        // UPDATE_FIELD, and it requires no contact target at all — a named person, or a person the
        // session has verified, satisfies contactTarget() and is handled by the ordinary path.
        updateIntent && updateSlotsComplete && !contactTarget() &&
            UPDATE_BUSINESS_CARD !in successfulTools -> MissingSlot.UPDATE_TARGET

        else -> null
    }

    fun pendingTerminalTool(): String? = when {
        terminalExecutionFailure || previewOnly || unsupportedRequest || explicitRealSend ||
            invalidEmailLike || contactLookupTerminal() -> null

        calendarIntent && calendarTimeProvided && CREATE_CALENDAR_EVENT !in successfulTools ->
            CREATE_CALENDAR_EVENT

        updateIntent && updateSlotsComplete && UPDATE_BUSINESS_CARD !in successfulTools ->
            UPDATE_BUSINESS_CARD

        composeIntent && hasExplicitComposeExecution() && hasResolvableRecipient() &&
            OPEN_COMPOSE !in successfulTools -> OPEN_COMPOSE

        else -> null
    }

    /**
     * A tool response that tells the model the workflow is unfinished and which tool finishes it.
     *
     * Sent through the ordinary tool-result channel rather than as a new user turn, so the native
     * conversation stays a single coherent exchange and the repair cannot be mistaken for something
     * the user said.
     */
    fun continuationPrompt(
        pendingTool: String,
        lastCallId: String,
        lastToolName: String,
    ): ModelWorkflowNote = ModelWorkflowNote(
        text = "요청을 완료하려면 $pendingTool 도구를 호출해야 합니다. 설명 대신 도구를 호출해 주세요.",
        pendingTool = pendingTool,
        afterCallId = lastCallId,
        afterToolName = lastToolName,
    )

    private fun contactLookupTerminal(): Boolean =
        contactTarget() && (
            searchResultIds?.isEmpty() == true ||
                (searchResultIds?.size ?: 0) > 1 ||
                selectedContact?.let { contact ->
                    when {
                        userText.contains("메일") || userText.contains("이메일") ->
                            contact.string("email").isNullOrBlank()
                        userText.contains("문자") ->
                            contact.string("mobile").isNullOrBlank() &&
                                contact.string("phone").isNullOrBlank()
                        else -> false
                    }
                } == true
            )

    private fun hasResolvableRecipient(): Boolean =
        directEmails.isNotEmpty() || directPhones.isNotEmpty() || contactTarget()

    private fun validateSearch(): WorkflowValidationResult {
        if (!(contactTarget() && (composeIntent || calendarIntent || updateIntent)) && !contactReadIntent) {
            return reject(
                WorkflowRejectReason.TOOL_NOT_REQUESTED,
                "연락처 검색이 필요한 요청이 아닙니다.",
            )
        }
        return WorkflowValidationResult.Allow
    }

    private fun validateGetContact(call: ModelToolCall): WorkflowValidationResult {
        val requestedId = call.arguments.string("card_id").orEmpty()
        if (requestedId.startsWith("card-") && userText.contains(requestedId)) {
            return WorkflowValidationResult.Allow
        }
        val ids = searchResultIds ?: return reject(
            WorkflowRejectReason.CONTACT_LOOKUP_REQUIRED,
            "먼저 연락처를 검색해야 합니다.",
            setOf(SEARCH_CONTACTS),
        )
        if (ids.isEmpty()) {
            return reject(
                WorkflowRejectReason.CONTACT_NOT_FOUND,
                "검색 결과가 없어 다음 작업을 실행할 수 없습니다.",
            )
        }
        if (ids.size > 1) {
            return reject(
                WorkflowRejectReason.CONTACT_SELECTION_REQUIRED,
                "같은 이름의 연락처가 여러 명입니다. 사용자가 대상을 선택해야 합니다.",
            )
        }
        if (requestedId != ids.single()) {
            return reject(
                WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
                "검색 결과에 포함된 명함 ID만 조회할 수 있습니다.",
                setOf(GET_CONTACT),
            )
        }
        return WorkflowValidationResult.Allow
    }

    private fun validateCompose(call: ModelToolCall): WorkflowValidationResult {
        if (!composeIntent || !hasExplicitComposeExecution()) {
            return reject(
                WorkflowRejectReason.TOOL_NOT_REQUESTED,
                "작성 화면 실행 의도가 명확하지 않아 도구를 실행하지 않습니다.",
            )
        }
        val channel = call.arguments.string("channel")
        val to = call.arguments.string("to").orEmpty()
        val body = call.arguments.string("body").orEmpty()
        if (body.isBlank()) {
            return invalid("메시지 본문은 비어 있을 수 없습니다.", setOf(OPEN_COMPOSE))
        }
        if (channel == "email") {
            if (!isValidEmail(to)) {
                return reject(WorkflowRejectReason.INVALID_EMAIL, "올바른 이메일 주소가 필요합니다.")
            }
            if (call.arguments.string("subject").isNullOrBlank()) {
                return invalid("이메일 제목은 비어 있을 수 없습니다.", setOf(OPEN_COMPOSE))
            }
        } else if (channel == "sms") {
            if (!isValidPhone(to)) {
                return reject(WorkflowRejectReason.INVALID_PHONE, "올바른 전화번호가 필요합니다.")
            }
            if (!call.arguments.string("subject").isNullOrBlank()) {
                return invalid("문자에는 subject를 넣을 수 없습니다.", setOf(OPEN_COMPOSE))
            }
        }

        if (contactTarget()) {
            val contact = selectedContact ?: return contactStageRejection()
            val verified = when (channel) {
                "email" -> setOfNotNull(contact.string("email")?.lowercase())
                "sms" -> setOfNotNull(
                    contact.string("mobile")?.let(::normalizePhone),
                    contact.string("phone")?.let(::normalizePhone),
                )
                else -> emptySet()
            }
            val normalizedTo = if (channel == "email") to.lowercase() else normalizePhone(to)
            if (normalizedTo !in verified) {
                return reject(
                    WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
                    "조회한 연락처의 주소 또는 번호만 사용할 수 있습니다.",
                    setOf(OPEN_COMPOSE),
                )
            }
        } else {
            val directMatch = when (channel) {
                "email" -> to.lowercase() in directEmails
                "sms" -> normalizePhone(to) in directPhones
                else -> false
            }
            if (!directMatch) {
                return reject(
                    WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
                    "사용자가 제공했거나 연락처 조회로 확인된 수신자만 사용할 수 있습니다.",
                )
            }
        }
        return WorkflowValidationResult.Allow
    }

    private fun validateCalendar(call: ModelToolCall): WorkflowValidationResult {
        if (!calendarIntent) {
            return reject(WorkflowRejectReason.TOOL_NOT_REQUESTED, "일정 생성 요청이 아닙니다.")
        }
        val rawStart = call.arguments.string("start_time").orEmpty()
        // The kernel has already run [normalizeArguments], so a zero-seconds value arrives here in
        // canonical form. Anything still not canonical is refused with the specific reason rather
        // than a generic "invalid datetime", because "you wrote seconds" and "that date does not
        // exist" are different problems for whoever has to fix them.
        val start = when (val canonical = LocalDateTimeCanonicalizer.canonicalize(rawStart)) {
            is LocalDateTimeCanonicalizer.Result.Canonical -> LocalDateTime.parse(canonical.value)
            is LocalDateTimeCanonicalizer.Result.Rejected -> return reject(
                WorkflowRejectReason.INVALID_DATETIME,
                canonical.reasonKo,
                setOf(CREATE_CALENDAR_EVENT),
            )
        }
        // end_time was never validated here, so a value the canonicalizer had already refused was
        // left in the call verbatim and travelled straight to the plugin. Both fields go through the
        // same contract, and a refusal is a validation error rather than a value that survives.
        call.arguments.string("end_time")?.let { rawEnd ->
            val end = when (val canonical = LocalDateTimeCanonicalizer.canonicalize(rawEnd)) {
                is LocalDateTimeCanonicalizer.Result.Canonical -> LocalDateTime.parse(canonical.value)
                is LocalDateTimeCanonicalizer.Result.Rejected -> return reject(
                    WorkflowRejectReason.INVALID_DATETIME,
                    "end_time: ${canonical.reasonKo}",
                    setOf(CREATE_CALENDAR_EVENT),
                )
            }
            if (!end.isAfter(start)) {
                return reject(
                    WorkflowRejectReason.INVALID_DATETIME,
                    "종료 시각은 시작 시각보다 늦어야 합니다.",
                    setOf(CREATE_CALENDAR_EVENT),
                )
            }
        }
        if (relativeDateIntent && currentDate == null) {
            return reject(
                WorkflowRejectReason.CURRENT_DATETIME_REQUIRED,
                "상대 날짜를 계산하려면 먼저 현재 날짜와 시각을 조회해야 합니다.",
                setOf(GET_CURRENT_DATETIME),
            )
        }
        expectedDate()?.let { expected ->
            if (start.toLocalDate() != expected) {
                return reject(
                    WorkflowRejectReason.INVALID_DATETIME,
                    "상대 또는 절대 날짜 계산 결과가 사용자 요청과 일치하지 않습니다.",
                    setOf(CREATE_CALENDAR_EVENT),
                )
            }
        }
        expectedTime()?.let { expected ->
            if (start.toLocalTime() != expected) {
                return reject(
                    WorkflowRejectReason.INVALID_DATETIME,
                    "일정 시작 시각이 사용자 요청과 일치하지 않습니다.",
                    setOf(CREATE_CALENDAR_EVENT),
                )
            }
        }
        if (contactTarget()) {
            val contact = selectedContact ?: return contactStageRejection()
            val verifiedEmail = contact.string("email")?.takeIf(::isValidEmail)
                ?: return reject(
                    WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
                    "조회한 연락처에 유효한 이메일 주소가 없습니다.",
                )
            val attendees = (call.arguments["attendee_emails"] as? JsonArray)
                .orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            if (verifiedEmail !in attendees) {
                return reject(
                    WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
                    "연락처 일정에는 조회로 확인한 참석자 이메일이 필요합니다.",
                    setOf(CREATE_CALENDAR_EVENT),
                )
            }
        }
        return WorkflowValidationResult.Allow
    }

    private fun validateUpdate(call: ModelToolCall): WorkflowValidationResult {
        if (!updateIntent) {
            return reject(WorkflowRejectReason.TOOL_NOT_REQUESTED, "명함 수정 요청이 아닙니다.")
        }
        if (contactTarget()) {
            val contact = selectedContact ?: return contactStageRejection()
            if (call.arguments.string("card_id") != contact.string("card_id")) {
                return reject(
                    WorkflowRejectReason.CONTACT_VALUE_NOT_VERIFIED,
                    "조회로 확인한 명함만 수정할 수 있습니다.",
                    setOf(UPDATE_BUSINESS_CARD),
                )
            }
        }
        val updates = call.arguments["updates"] as? JsonObject
        val clears = call.arguments["clear_fields"] as? JsonArray
        if (updates.isNullOrEmpty() && clears.isNullOrEmpty()) {
            return invalid("수정할 필드와 값이 필요합니다.")
        }
        val overlap = updates.orEmpty().keys intersect clears.orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        if (overlap.isNotEmpty()) {
            return invalid("같은 필드를 수정하면서 동시에 비울 수 없습니다.", setOf(UPDATE_BUSINESS_CARD))
        }
        return WorkflowValidationResult.Allow
    }

    private fun validateCurrentDatetime(): WorkflowValidationResult {
        if (!currentTimeIntent && !relativeDateIntent) {
            return reject(
                WorkflowRejectReason.UNNECESSARY_TOOL_CALL,
                "현재 시각 조회가 필요하지 않은 요청입니다.",
                when {
                    calendarIntent -> setOf(CREATE_CALENDAR_EVENT)
                    composeIntent -> setOf(OPEN_COMPOSE)
                    else -> emptySet()
                },
            )
        }
        return WorkflowValidationResult.Allow
    }

    private fun contactStageRejection(): WorkflowValidationResult {
        val ids = searchResultIds
        return when {
            ids == null -> reject(
                WorkflowRejectReason.CONTACT_LOOKUP_REQUIRED,
                "이름으로 지정된 연락처입니다. 먼저 연락처를 검색해야 합니다.",
                setOf(SEARCH_CONTACTS),
            )
            ids.isEmpty() -> reject(
                WorkflowRejectReason.CONTACT_NOT_FOUND,
                "검색 결과가 없어 다음 작업을 실행할 수 없습니다.",
            )
            ids.size > 1 -> reject(
                WorkflowRejectReason.CONTACT_SELECTION_REQUIRED,
                "같은 이름의 연락처가 여러 명입니다. 사용자가 대상을 선택해야 합니다.",
            )
            else -> reject(
                WorkflowRejectReason.CONTACT_DETAIL_REQUIRED,
                "주소나 번호를 확인하려면 연락처 상세 조회가 필요합니다.",
                setOf(GET_CONTACT),
            )
        }
    }

    private fun validateSchema(arguments: JsonObject, schema: JsonObject): WorkflowRejection? {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        val unknown = arguments.keys - properties.keys
        if (unknown.isNotEmpty() && (schema["additionalProperties"] as? JsonPrimitive)?.content == "false") {
            return WorkflowRejection(
                WorkflowRejectReason.INVALID_ARGUMENTS,
                "허용되지 않은 argument가 있습니다: ${unknown.sorted().joinToString()}",
            )
        }
        for (name in required) {
            val value = arguments[name]
                ?: return WorkflowRejection(
                    WorkflowRejectReason.INVALID_ARGUMENTS,
                    "필수 argument가 없습니다: $name",
                )
            if (value is JsonPrimitive && value.isString && value.content.isBlank()) {
                return WorkflowRejection(
                    WorkflowRejectReason.INVALID_ARGUMENTS,
                    "필수 argument는 비어 있을 수 없습니다: $name",
                )
            }
        }
        for ((name, value) in arguments) {
            val property = properties[name] as? JsonObject ?: continue
            val type = property.string("type")
            val typeValid = when (type) {
                "string" -> value is JsonPrimitive && value.isString
                "integer" -> value is JsonPrimitive && value.intOrNull != null
                "array" -> value is JsonArray
                "object" -> value is JsonObject
                "boolean" -> value is JsonPrimitive && !value.isString &&
                    value.content in setOf("true", "false")
                else -> true
            }
            if (!typeValid) {
                return WorkflowRejection(
                    WorkflowRejectReason.INVALID_ARGUMENTS,
                    "argument 타입이 올바르지 않습니다: $name",
                )
            }
            val allowed = (property["enum"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }
            if (allowed.isNotEmpty() && (value as? JsonPrimitive)?.content !in allowed) {
                return WorkflowRejection(
                    WorkflowRejectReason.INVALID_ARGUMENTS,
                    "argument 값이 허용 범위를 벗어났습니다: $name",
                )
            }
            if (value is JsonPrimitive && value.isString &&
                name in NON_EMPTY_FIELDS && value.content.isBlank()
            ) {
                return WorkflowRejection(
                    WorkflowRejectReason.INVALID_ARGUMENTS,
                    "argument는 비어 있을 수 없습니다: $name",
                )
            }
            if (value is JsonArray &&
                ((property["items"] as? JsonObject)?.string("format") == "email") &&
                value.any { !isValidEmail((it as? JsonPrimitive)?.content.orEmpty()) }
            ) {
                return WorkflowRejection(
                    WorkflowRejectReason.INVALID_EMAIL,
                    "유효하지 않은 이메일 주소가 포함되어 있습니다.",
                )
            }
        }
        return null
    }

    private fun expectedDate(): LocalDate? {
        val base = currentDate
        if (relativeDateIntent && base != null) {
            if (userText.contains("내일")) return base.plusDays(1)
            if (userText.contains("오늘")) return base
            NEXT_WEEKDAY_REGEX.find(userText)?.groupValues?.getOrNull(1)?.let { label ->
                val target = KOREAN_WEEKDAYS[label] ?: return@let
                val nextMonday = base.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                return nextMonday.plusDays((target.value - DayOfWeek.MONDAY.value).toLong())
            }
        }
        ABSOLUTE_DATE_REGEX.find(userText)?.destructured?.let { (year, month, day) ->
            return try {
                LocalDate.of(year.toInt(), month.toInt(), day.toInt())
            } catch (_: RuntimeException) {
                null
            }
        }
        return null
    }

    private fun expectedTime(): LocalTime? {
        val match = CALENDAR_TIME_REGEX.find(userText) ?: return null
        val marker = match.groupValues[1]
        var hour = match.groupValues[2].toInt()
        val minute = match.groupValues[3].takeIf(String::isNotBlank)?.toInt() ?: 0
        if (marker == "오후" && hour < 12) hour += 12
        if (marker == "오전" && hour == 12) hour = 0
        return try {
            LocalTime.of(hour, minute)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun hasExplicitComposeExecution(): Boolean =
        EXPLICIT_COMPOSE_MARKERS.any(userText::contains) ||
            (EXTERNAL_UI_MARKERS.any(userText::contains) && composeIntent)

    private fun contactTarget(): Boolean = contactNameTarget || trustedSessionContactTarget

    private fun invalid(
        message: String,
        allowed: Set<String> = emptySet(),
    ) = reject(WorkflowRejectReason.INVALID_ARGUMENTS, message, allowed)

    private fun reject(
        reason: WorkflowRejectReason,
        message: String,
        allowed: Set<String> = emptySet(),
    ) = WorkflowValidationResult.Reject(WorkflowRejection(reason, message, allowed))

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

    private fun List<JsonElement>.isNullOrEmpty() = isEmpty()

    companion object {
        const val SEARCH_CONTACTS = "search_contacts"
        const val GET_CONTACT = "get_contact"
        const val UPDATE_BUSINESS_CARD = "update_business_card"
        const val CREATE_CALENDAR_EVENT = "create_calendar_event"
        const val OPEN_COMPOSE = "open_compose"
        const val GET_CURRENT_DATETIME = "get_current_datetime"

        private val NON_EMPTY_FIELDS = setOf(
            "query", "card_id", "channel", "to", "body", "title", "start_time",
        )
        private val EMAIL_REGEX =
            Regex("""(?i)\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+(?![A-Z0-9.-])""")
        private val PHONE_REGEX = Regex("""(?<!\d)(?:\+82[- ]?|0)\d{1,2}[- ]?\d{3,4}[- ]?\d{4}(?!\d)""")
        private val INVALID_EMAIL_LIKE_REGEX =
            Regex("""(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+(?:-at-|(?:\s+at\s+))[a-z0-9.-]+(?![a-z0-9.-])""")
        private val MALFORMED_SPACED_EMAIL_REGEX =
            Regex("""(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+\s+[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}(?![a-z0-9.-])""")
        /**
         * No trailing `\b?`: an optional word boundary matches the empty string unconditionally, so
         * it changed nothing here — but Android's regex engine rejects a quantifier on a zero-width
         * assertion outright, and this pattern is compiled in a companion object, so the whole
         * policy class failed to initialise on the first turn of any real device run.
         */
        /**
         * A person named as the other party of the request.
         *
         * 이랑/랑/하고 are the everyday spoken forms of 와/과 — "표하윤이랑 일정 잡아줘" is the same
         * request as "표하윤과 일정 잡아줘". Recognising only the written forms meant the workflow
         * decided no contact was involved, refused the lookup, and the schedule lost the person it
         * was for. 께 is the honorific of 에게 and was missing for the same reason.
         */
        private val NAME_RECIPIENT_REGEX =
            Regex("""[가-힣]{2,12}(?:에게|한테|께|이랑|랑|하고|와|과)""")
        private val NAME_CARD_REGEX = Regex("""[가-힣]{2,12}\s*명함""")
        /** Arguments whose value is a device-local datetime. */
        private val DATETIME_ARGUMENTS = listOf("start_time", "end_time")
        private val ABSOLUTE_DATE_REGEX = Regex("""(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일""")
        private val CALENDAR_TIME_REGEX =
            Regex("""(오전|오후)\s*(\d{1,2})시(?:\s*(\d{1,2})분)?""")
        private val NEXT_WEEKDAY_REGEX = Regex("""다음\s*주\s*(월|화|수|목|금|토|일)요일""")
        private val KOREAN_WEEKDAYS = mapOf(
            "월" to DayOfWeek.MONDAY,
            "화" to DayOfWeek.TUESDAY,
            "수" to DayOfWeek.WEDNESDAY,
            "목" to DayOfWeek.THURSDAY,
            "금" to DayOfWeek.FRIDAY,
            "토" to DayOfWeek.SATURDAY,
            "일" to DayOfWeek.SUNDAY,
        )
        private val PREVIEW_MARKERS = listOf("예시", "방법", "먼저 작성해서 보여", "초안을 먼저 보여")
        private val EXTERNAL_UI_MARKERS = listOf("화면 열어", "작성 화면", "캘린더 열어")
        private val UNSUPPORTED_REQUEST_MARKERS =
            listOf("삭제해", "삭제 해", "완전히 삭제", "전화 걸어", "전화해줘", "전화해 줘")
        private val REAL_SEND_MARKERS = listOf("실제로 전송", "지금 바로 전송", "자동 전송")
        /** One vocabulary, shared with the pre-router. See [ActionVocabulary]. */
        private val CALENDAR_MARKERS = ActionVocabulary.CALENDAR
        private val COMPOSE_MARKERS = ActionVocabulary.COMPOSE
        private val CARD_OBJECT_MARKERS = ContactReadIntent.CARD_OBJECTS
        /** Editable card fields, named the way a user names them. */
        private val CARD_FIELD_MARKERS = listOf(
            "메모", "직함", "직책", "직급", "회사", "부서", "이메일", "메일 주소", "메일주소",
            "전화번호", "휴대폰", "핸드폰", "주소", "업종", "지역", "홈페이지", "웹사이트", "이름",
        )
        /** "VIP로", "영업팀장으로", "‘VIP’라고" — a concrete new value, not just a field name. */
        private val UPDATE_VALUE_REGEX = Regex(CardUpdateIntent.UPDATE_VALUE_PATTERN)
        private val RELATIVE_DATE_MARKERS = listOf("오늘", "내일", "모레", "다음 주", "이번 주")
        private val EXPLICIT_COMPOSE_MARKERS =
            listOf("작성해줘", "작성해 줘", "작성해", "써줘", "써 줘", "초안 작성")
        /**
         * Claims the agent is not entitled to make, keyed by what the tool actually did.
         *
         * The distinction is semantic, not lexical. `open_compose` opens a composer — the user still
         * has to press send — so "전송했습니다" is false whether or not the tool succeeded.
         * `create_calendar_event` opens an insert screen, so "저장했습니다" is false the same way.
         * `update_business_card` genuinely writes, so its completion claim is only false when the
         * write did not happen.
         *
         * Grouping them by tool is what makes this a check on meaning rather than a banned-word list:
         * the same sentence is honest after one tool and false after another.
         */
        /** Tools whose success is the only thing that can justify a completion claim. */
        private val SIDE_EFFECTING_TOOLS = setOf(OPEN_COMPOSE, CREATE_CALENDAR_EVENT, UPDATE_BUSINESS_CARD)

        private val SCREEN_ONLY_COMPLETION_CLAIMS: Map<String, List<String>> = mapOf(
            OPEN_COMPOSE to listOf(
                "전송했습니다", "전송 완료", "보냈습니다", "발송했습니다", "발송 완료", "전송하였습니다",
            ),
            CREATE_CALENDAR_EVENT to listOf(
                "일정을 생성했습니다", "일정을 저장했습니다", "일정이 생성되었습니다", "일정이 저장되었습니다",
                "일정 등록 완료", "캘린더에 저장했습니다", "일정을 등록했습니다",
            ),
        )

        /** What a screen-opening tool may honestly claim. */
        private val SCREEN_ONLY_CORRECTION: Map<String, String> = mapOf(
            OPEN_COMPOSE to "작성 화면을 열었습니다. 실제 전송 여부는 작성 화면에서 확인해 주세요.",
            CREATE_CALENDAR_EVENT to
                "일정 작성 화면을 열었습니다. 실제 저장 여부는 캘린더 앱에서 확인해 주세요.",
        )

        /** Claims that assert work happened at all. False whenever no tool did that work. */
        private val COMPLETION_CLAIMS = listOf(
            "완료했습니다", "완료되었습니다", "처리했습니다", "수정했습니다", "저장했습니다",
            "생성했습니다", "등록했습니다", "보냈습니다", "전송했습니다", "발송했습니다",
        )

        fun isValidEmail(value: String): Boolean = EMAIL_REGEX.matches(value.trim())

        fun isValidPhone(value: String): Boolean = PHONE_REGEX.matches(value.trim())

        fun normalizePhone(value: String): String = value.filter(Char::isDigit).let {
            if (it.startsWith("82") && !it.startsWith("820")) "0" + it.drop(2) else it
        }
    }
}
