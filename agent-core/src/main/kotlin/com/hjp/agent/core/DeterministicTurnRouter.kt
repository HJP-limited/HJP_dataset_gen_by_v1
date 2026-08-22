package com.hjp.agent.core

import com.hjp.agent.contract.ClarifyReason
import com.hjp.agent.contract.ContactCandidate
import com.hjp.agent.contract.ContactMention
import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.contract.ModelConversationRole
import com.hjp.agent.contract.TrackedActionStatus
import com.hjp.agent.contract.TranscriptEntry
import com.hjp.agent.contract.TurnContext
import com.hjp.agent.contract.TurnRoutePlan

/**
 * Rule-based pre-router shared by every execution path.
 *
 * It never builds a side-effecting tool call. It answers five questions only: is this request about
 * the conversation itself, is it a general question that merely shares vocabulary with an action,
 * does it depend on a remembered target, is that target unambiguous and tool-verified, and is the
 * capability supported at all. Everything else is left to the existing parsers, the workflow
 * validator and the model.
 *
 * [route] and [act] are two views of one decision, so a route can never disagree with the
 * classification an evaluator asserts against.
 */
object DeterministicTurnRouter {
    private val realSendMarkers = Regex(
        "(?:(?:실제로|자동(?:으로)?|지금\\s*바로).{0,12}(?:전송|발송|보내)|" +
            "(?:전송|발송|보내).{0,12}(?:실제로|자동(?:으로)?|지금\\s*바로))",
    )
    private val deleteMarkers = Regex("(?:삭제|지워|제거)\\s*(?:해|하)?")
    private val phoneCallMarkers = Regex("(?:전화|통화).{0,8}(?:걸어|해\\s*줘|연결)")

    private val actionMarkers = listOf(
        "메일", "이메일", "문자", "sms", "작성", "써줘", "써 줘", "보내",
        "일정", "캘린더", "미팅", "회의", "약속", "등록",
    ) + CardUpdateIntent.UPDATE_VERBS
    private val conversationScopeMarkers = listOf(
        "방금", "아까", "앞서", "이전", "지금까지", "그동안", "대화", "우리가", "말한", "얘기한", "언급한",
    )
    private val historyQuestionMarkers = listOf(
        "누구", "누구야", "정리", "목록", "리스트", "요약", "뭐라고", "무슨 말", "뭐였", "어떤 사람",
    )
    private val failureQuestionMarkers = listOf("왜 실패", "왜 안", "실패 이유", "왜 못", "무슨 문제")
    private val cardObjectMarkers = ContactReadIntent.CARD_OBJECTS
    private val searchVerbMarkers = ContactReadIntent.SEARCH_VERBS
    private val pronounMarkers = ContactAnaphora.MARKERS
    private val referenceSearchMarkers = listOf("방금 찾은", "아까 찾은", "앞서 찾은", "이전에 찾은", "방금 검색한")

    /**
     * Words that point at something already said, and words that ask for it back.
     *
     * A recall needs both. "아까" alone opens plenty of ordinary requests ("아까 그 사람에게 메일
     * 써줘"), and "뭐였지" alone can ask about something never discussed. Together they are asking
     * the conversation to repeat itself, which is answerable without touching a card.
     */
    private val pastReferenceMarkers = listOf(
        "아까", "방금", "전에", "앞서", "이전에", "지난번", "먼젓번",
        "말했던", "말한", "얘기한", "알려줬던", "알려준", "찾았던", "찾은", "봤던", "본", "언급한",
    )
    private val recallQuestionMarkers = listOf(
        "뭐였", "뭐라고 했", "뭐랬", "어디였", "어디라고 했", "어디랬", "누구였", "누구라고 했",
        "언제였", "언제라고 했", "기억나", "기억해", "말했었", "알려줬지", "알려줬었",
        "했지", "였지", "이었지", "라고 했", "라고 그랬",
    )

    /**
     * Words that ask for the state of the world *now*, and the two things that can be done about it.
     *
     * Freshness on its own is not a request — "지금 시간 알려줘" is about the clock. Paired with a
     * search verb it means go and look again; paired with a read verb it means re-read the card that
     * is already in focus. Keeping the two apart is the difference between finding a different
     * person and refreshing this one.
     */
    private val freshnessMarkers = listOf("현재", "최신", "지금", "다시", "새로", "새롭게")
    private val freshSearchPredicates = listOf("검색", "찾아", "찾아봐", "찾아줘", "찾아 줘", "서치")

    /** Verbs that mean "read that card again" on their own. */
    private val freshDetailPredicates = listOf("조회", "상세", "명함 정보", "명함정보", "카드 정보", "다시 읽")

    /**
     * "확인" means far too many things by itself — "내용은 확인 부탁드립니다" is the body of a mail.
     * It only asks for a card to be re-read when the sentence also names a card.
     */
    private val freshDetailWeakPredicates = listOf("확인")
    private val refusalMarkers = Regex("지\\s*마|하지\\s*말|말아\\s*줘|말아라|하지\\s*마세요")

    /** "…한다면 어떤 결과가 나와?" — a condition being explored, not an instruction. */
    private val conditionalQuestionMarkers =
        Regex("(?:면|다면|라면)\\s*(?:어떻|어떤|어찌|무슨|뭐|어때|결과)")
    private val correctionMarkers = listOf("아니", "말고", "잘못", "틀렸", "정정")
    private val firstMentionMarkers = listOf("처음 말한", "처음 언급한", "맨 처음", "가장 먼저 말한", "처음에 말한")
    private val personWords = listOf("사람", "분", "명함", "연락처", "담당자", "고객")

    /**
     * Attribute words mapped to the card field they ask for. The mapping is what makes an attribute
     * question answerable from a fresh card read instead of from prose.
     */
    private val attributeFields = listOf(
        "회사" to "company",
        "직급" to "title",
        "직책" to "title",
        "직함" to "title",
        "부서" to "department",
        "이메일" to "email",
        "메일주소" to "email",
        "메일 주소" to "email",
        "휴대폰" to "mobile",
        "핸드폰" to "mobile",
        "전화" to "phone",
        "번호" to "mobile",
        "주소" to "address",
        "지역" to "location",
        "업종" to "industry",
        "메모" to "memo",
    )
    /**
     * Attribute words that, at the *start* of a sentence, imply "그 사람의 …" and therefore point at
     * the remembered target. Deliberately narrower than [attributeFields]: "메모 3 확인만 해줘" is a
     * note of its own, not a question about anyone's card, so a field name alone must not turn an
     * unrelated sentence into a contact reference.
     */
    private val attributeStarters = listOf(
        "회사", "직급", "직책", "직함", "부서", "번호", "전화", "이메일", "메일", "주소", "지역", "업종",
    )

    /**
     * Words that make a sentence a question about a concept rather than a command about a person.
     * They are required to co-occur with an explanation request, so "형식" inside "제목은 형식으로"
     * cannot flip an ordinary action into an information answer.
     */
    private val informationTopicMarkers = listOf(
        "형식", "방법", "사용법", "쓰는 법", "쓰는법", "뜻", "의미", "정의", "개념",
        "차이", "종류", "예시", "규칙", "문법", "포맷",
        // General-knowledge nouns. The category, not the sentences: the router used to know only
        // "형식/방법/뜻"-shaped topics, so "명함 스캔은 어떤 원리로 되는 거야?" and "업무용 문자 예절
        // 알려줘" shared enough vocabulary with an action to be routed as one.
        "원리", "예절", "매너", "체계", "요령", "노하우", "유래", "장단점", "상식", "기준점",
        "가이드", "관행", "에티켓", "구조", "역사",
    )
    private val explanationRequestMarkers = listOf(
        "알려줘", "알려 줘", "알려주세요", "알려주실", "설명해", "설명 해", "설명 좀", "뭐야", "뭔가요",
        "무엇", "어떻게 돼", "어떻게 되", "어떤가요", "궁금", "가르쳐", "되는 거야", "되는거야",
        // Polite interrogatives. Without them "이메일 제목은 어떤 형식으로 쓰면 되나요?" was not an
        // explanation request, so the leading attribute word made it a reference to the remembered
        // contact and the agent asked which person was meant — for a question about no person at all.
        "되나요", "될까요", "하나요", "인가요", "좋을까요", "쓰면", "적으면", "해야 하나", "해야 할까",
    )

    fun route(context: TurnContext): TurnRoutePlan = decide(context).plan

    /** The classification behind [route]. Same input, same decision, no second rule set. */
    fun act(context: TurnContext): DialogueAct = decide(context).act

    private data class Decision(val act: DialogueAct, val plan: TurnRoutePlan)

    private fun decide(context: TurnContext): Decision {
        val raw = context.userText.trim()
        if (raw.isEmpty()) return Decision(DialogueAct.OTHER, TurnRoutePlan.Continue(raw))
        val squeezed = raw.noSpaces()
        // Keyword-only questions read this; anything that needs the real words reads [raw].
        val squeezedForKeywords = PersonNameMask.maskNames(raw)

        // What kind of move is this, before what the agent can do about it. A capability rule applied
        // to reported, recalled, negated, hypothetical or capability-question speech refuses a
        // request the user never made — which is exactly what "내가 명함 지워달라고 했나?" got.
        val performsARequest = !ReportedSpeechIntent.isAboutAnActionRatherThanARequest(raw)
        if (performsARequest) {
            capabilityVeto(raw)?.let { return Decision(DialogueAct.UNSUPPORTED, it) }
        }

        // A destination this agent cannot reach settles the turn before any keyword inside the
        // request gets to classify it: "회의록 요약해서 슬랙에 올려줘" contains 회의, but what makes it
        // undoable is Slack. Answering with a generic capability blurb left the user unsure whether
        // the request had been understood, so the missing integration is named.
        externalIntegration(raw)?.let { return Decision(DialogueAct.OTHER, it) }

        // Recalling a past instruction is a question about the conversation, not the instruction.
        //
        // A question about a contact's field is normally read from the card, because the remembered
        // projection can be stale. But "아까 …라고 했지?" is not asking what is true now — it is
        // asking what was said, and the honest answer to that is what was said. Reading the card
        // would answer a different question, and would quietly refresh the contact's provenance on
        // a turn the user never asked to verify anything.
        //
        // So an explicit backward reference settles the turn before the contact-detail rule sees it.
        // Without both halves — a word pointing backwards and a question asking for it back — this
        // stays out of the way of ordinary requests that merely mention 아까.
        if (isHistoricalRecall(raw) ||
            (QuotedRecallIntent.isPresent(raw) && !asksAboutRememberedContact(raw, context))
        ) {
            return Decision(
                DialogueAct.QUOTED_RECALL,
                TurnRoutePlan.AnswerFromHistory(recallAnswer(raw, context)),
            )
        }

        // The other direction: a request that explicitly asks for current information must actually
        // go and get it. Answering "지금 정보를 다시 검색해줘" from memory returns a value from then to
        // a question about now, which is the same mistake in reverse.
        freshLookupIntent(raw, squeezedForKeywords, performsARequest)?.let { lookup ->
            val named = namedKnownPerson(raw, context)
            val focus = context.memory.selectedContact?.takeIf { it.isActionable }
            return when (lookup) {
                // Search by the verified name, never by the whole sentence: "최신 회사 정보" is not a
                // person. A name the turn itself supplies wins over the remembered focus, so a
                // request about somebody new never falls back to whoever was in focus before.
                FreshLookup.SEARCH -> {
                    val target = named?.name ?: focus?.name
                    if (target.isNullOrBlank()) noKnownTarget()
                    else Decision(DialogueAct.CONTACT_SEARCH, TurnRoutePlan.Continue("$target 명함 찾아줘"))
                }
                // Re-reading is only meaningful for a card already established, and only for the one
                // in focus. If the sentence names somebody else, this is not a refresh of the focus
                // and the ordinary target rules decide it.
                FreshLookup.DETAIL -> when {
                    focus == null -> noKnownTarget()
                    named != null && named.cardId != focus.cardId ->
                        return@let // fall through to the ordinary rules below
                    else -> Decision(
                        DialogueAct.CONTACT_DETAIL,
                        TurnRoutePlan.ContactDetail(focus.cardId, focus.name),
                    )
                }
            }
        }

        // A question about a concept must be settled before any target-resolution rule sees it:
        // "이메일 형식이 어떻게 되는지 알려줘" names no person, so asking *which* person would be a
        // clarification about a target the user never referred to.
        generalInformation(raw, context)?.let {
            return Decision(DialogueAct.GENERAL_INFORMATION, it)
        }

        if (firstMentionMarkers.any(raw::contains)) {
            val first = context.memory.activeMentions.minByOrNull { it.order }
                ?: return Decision(
                    DialogueAct.CLARIFICATION_REQUIRED,
                    TurnRoutePlan.Clarify(
                        "이 대화에서 처음 언급된 연락처가 없습니다. 이름을 알려 주세요.",
                        ClarifyReason.NO_KNOWN_TARGET,
                    ),
                )
            return targetedPlan(raw, first.cardId, first.name, DialogueAct.CONTACT_SELECTION)
        }

        val hasAction = actionMarkers.any { squeezedForKeywords.contains(it, ignoreCase = true) }
        val conversationScoped = conversationScopeMarkers.any(raw::contains)

        // "방금 그거 왜 실패했어?" is scoped to the conversation *and* asks about a failure. It is
        // the failure question first: classifying it as a generic history question would lose the
        // distinction an evaluator needs, even though both are answered from the same record.
        val asksAboutFailure = failureQuestionMarkers.any(raw::contains)
        if (asksAboutFailure) {
            answerFromHistory(raw, context)?.let {
                return Decision(DialogueAct.FAILURE_QUESTION, it)
            }
        }
        if (!hasAction && conversationScoped) {
            val wantsCardSearch = cardObjectMarkers.any(raw::contains) &&
                searchVerbMarkers.any(raw::contains)
            if (!wantsCardSearch) {
                answerFromHistory(raw, context)?.let {
                    return Decision(DialogueAct.HISTORY_QUESTION, it)
                }
            }
        }

        // An ordinal only selects a contact when the sentence is about a person; "두 번째 질문"
        // must not be read as "the second search result".
        val ordinal = ordinalIndex(raw, squeezed)
        if (ordinal != null && personWords.any(raw::contains)) {
            val candidate = context.memory.candidateContacts.getOrNull(ordinal)
                ?: return Decision(
                    DialogueAct.CLARIFICATION_REQUIRED,
                    TurnRoutePlan.Clarify(
                        "몇 번째 연락처인지 확인하지 못했습니다. 이름을 알려 주세요.",
                        ClarifyReason.UNGROUNDED_REFERENCE,
                    ),
                )
            return targetedPlan(raw, candidate.cardId, candidate.name, DialogueAct.CONTACT_SELECTION)
        }

        // "A 말고 B" replaces the target. Passing the sentence through unchanged would search for
        // both people at once and leave the rejected one selectable.
        replacement(raw)?.let { (rejected, remainder) ->
            return Decision(
                DialogueAct.CORRECTION,
                TurnRoutePlan.CorrectionReplacement(remainder, rejected),
            )
        }
        // Other corrections ("아니, 박민수 말한 거야") still replace the target, but they carry the new
        // name themselves; resolving a pronoun from the old selection would re-target the rejected
        // person, so the turn is parsed from scratch.
        if (correctionMarkers.any(raw::contains)) {
            return Decision(DialogueAct.CORRECTION, TurnRoutePlan.Continue(raw))
        }

        // An attribute noun at the front of a sentence means "그 사람의 …" only when the sentence is
        // *asking* about it. "메일 주소가 뭐야?" is a question about the person in focus; "메일
        // 작성해줘." is a command with no recipient in it, and treating the second as a reference
        // silently addressed whoever had last been looked up — and then failed without ever asking
        // the user who the mail was for. 문자 is not an attribute starter, so the two channels used
        // to behave differently for the same request shape; they no longer do.
        // Read from the *masked* sentence, like every other keyword test. Against the raw text a
        // person whose name merely begins with an attribute word — 메일리, 주소연 — turned their own
        // lookup into a question about the remembered contact, and with nobody remembered the turn
        // asked who was meant instead of searching for them.
        val attributeStarterReference =
            attributeStarters.any { squeezedForKeywords.noSpaces().startsWith(it.noSpaces()) } &&
                requestedFields(raw) != null
        val referencesMemory = pronounMarkers.any(raw::contains) ||
            referenceSearchMarkers.any(raw::contains) ||
            attributeStarterReference
        if (!referencesMemory) return Decision(actOf(raw), TurnRoutePlan.Continue(raw))

        if (mentionsKnownName(raw, context)) return Decision(actOf(raw), TurnRoutePlan.Continue(raw))

        val selected = context.memory.selectedContact
        if (selected != null && selected.isActionable) {
            return targetedPlan(raw, selected.cardId, selected.name, actOf(raw))
        }
        val candidates = context.memory.candidateContacts
        if (candidates.size > 1) {
            return Decision(
                DialogueAct.CLARIFICATION_REQUIRED,
                TurnRoutePlan.Clarify(
                    "대상이 여러 명입니다. ${candidates.joinToString(", ") { it.distinguishingLabel }} 중 누구인지 알려 주세요.",
                    ClarifyReason.AMBIGUOUS_TARGET,
                ),
            )
        }
        return Decision(
            DialogueAct.CLARIFICATION_REQUIRED,
            TurnRoutePlan.Clarify(
                "어떤 분을 말씀하시는지 알려 주세요. 이 대화에서 확인된 연락처가 없습니다.",
                ClarifyReason.NO_KNOWN_TARGET,
            ),
        )
    }

    /**
     * Routes a turn that already has one verified target.
     *
     * A request that only wants to *know* something about that person is answered from a fresh card
     * read, deterministically. A request that wants to *do* something still goes to the model, which
     * owns argument extraction, with the target pinned so it cannot drift.
     */
    private fun targetedPlan(
        raw: String,
        cardId: String,
        name: String,
        fallbackAct: DialogueAct,
    ): Decision {
        val fields = requestedFields(raw)
        if (fields != null) {
            // How the target was *chosen* decides the label, not how much of the card is shown.
            // An ordinal or "처음 말한 사람" picks one of several people, which is a selection;
            // "그분 연락처 좀 알려줘" acts on the person already in focus, which is a read of their
            // card. Keying this off `fields.isEmpty()` instead reported the second one as a
            // selection purely because the user asked for the whole card rather than one field.
            return Decision(
                if (fallbackAct == DialogueAct.CONTACT_SELECTION) DialogueAct.CONTACT_SELECTION
                else DialogueAct.CONTACT_DETAIL,
                TurnRoutePlan.ContactDetail(cardId, name, fields),
            )
        }
        return Decision(
            fallbackAct,
            TurnRoutePlan.GroundedContact(
                text = rewrite(raw, name).ifBlank { raw },
                cardId = cardId,
                name = name,
                requiresFreshRead = true,
            ),
        )
    }

    /**
     * Which card fields this sentence is asking to see, or null when it is not a read request at all.
     *
     * An empty list means "show the whole card". A sentence that also commands an action is never a
     * read request: "그 사람 메모를 VIP로 수정해줘" mentions 메모 but is an update.
     */
    private fun requestedFields(raw: String): List<String>? {
        if (actionMarkers.any { raw.contains(it, ignoreCase = true) } && !isPureAttributeQuestion(raw)) {
            return null
        }
        val fields = attributeFields.filter { raw.contains(it.first) }.map { it.second }.distinct()
        if (fields.isNotEmpty() && isQuestionOrShowRequest(raw)) return fields
        val wantsCard = cardObjectMarkers.any(raw::contains) &&
            (raw.contains("보여") || raw.contains("알려") || raw.contains("확인"))
        return if (wantsCard) emptyList() else null
    }

    /** "메모가 뭐야?" asks; "메모를 VIP로 수정해줘" commands. Only the first is a read. */
    private fun isPureAttributeQuestion(raw: String): Boolean {
        val commanding = CardUpdateIntent.UPDATE_VERBS +
            listOf("작성", "보내", "만들", "등록", "열어")
        return commanding.none(raw::contains) && isQuestionOrShowRequest(raw)
    }

    private fun isQuestionOrShowRequest(raw: String): Boolean =
        raw.endsWith("?") || raw.endsWith("？") ||
            (listOf("뭐야", "뭔가요", "무엇", "어디", "어때", "몇", "누구") + ContactReadIntent.SHOW_VERBS)
                .any(raw::contains)

    /**
     * A concept question, or null when the sentence points at something concrete.
     *
     * The exclusions are what keep this from swallowing real work: a literal address, a name this
     * session knows, a pronoun, or an ordinal all mean the user is talking about a specific target.
     */
    private fun generalInformation(raw: String, context: TurnContext): TurnRoutePlan? {
        val topic = informationTopicMarkers.firstOrNull(raw::contains) ?: return null
        if (explanationRequestMarkers.none(raw::contains)) return null
        if (CONTACT_VALUE_REGEX.containsMatchIn(raw)) return null
        if (pronounMarkers.any(raw::contains) || referenceSearchMarkers.any(raw::contains)) return null
        if (firstMentionMarkers.any(raw::contains)) return null
        if (ordinalIndex(raw, raw.noSpaces()) != null) return null
        if (mentionsKnownName(raw, context)) return null
        // "그 형식으로 메일 써줘" carries an explicit execution verb; it is a command, not a question.
        if (EXECUTION_VERB_REGEX.containsMatchIn(raw)) return null
        // A card search is a real request even though "알려줘" is an explanation marker.
        if (cardObjectMarkers.any(raw::contains) && searchVerbMarkers.any(raw::contains)) return null
        return TurnRoutePlan.GeneralInformation(
            "‘$topic’에 대한 일반 정보 질문으로 이해했습니다. 이 에이전트는 일반 지식에는 답변하지 않고, " +
                "저장된 명함 검색·조회·수정과 메일/문자 작성 화면 열기, 일정 작성 화면 열기를 처리합니다. " +
                "명함과 관련된 작업이면 대상과 내용을 알려 주세요.",
        )
    }

    /** Splits "A 말고 B …" into the rejected term and the sentence that names only B. */
    private fun replacement(raw: String): Pair<String, String>? {
        val match = REPLACEMENT_REGEX.find(raw) ?: return null
        val rejected = match.groupValues[1].trim().trim(',', '.', ' ')
        val remainder = raw.substring(match.range.last + 1).trim()
        if (rejected.length < 2 || remainder.isBlank()) return null
        return rejected to remainder
    }

    /** Answers from the session's own record; it never re-runs the quoted instruction. */
    private fun recallAnswer(raw: String, context: TurnContext): String {
        // A recall can be about a fact the user stated rather than a request they made. "아까 회사가
        // 어디라고 했지?" is answerable from memory, and answering "그런 요청을 하신 기록은 없습니다"
        // is both wrong and needlessly sends the user to look it up again.
        rememberedFactAnswer(raw, context)?.let { return it }
        val prior = priorTranscript(raw, context)
        val quoted = QUOTE_REGEX.find(raw)?.groupValues?.getOrNull(1)?.trim()
        if (quoted != null) {
            val matching = prior.firstOrNull { it.text.contains(quoted) }
            return if (matching != null) {
                "네, 이 대화에서 “$quoted”라고 말씀하셨습니다. 다시 실행하려면 말씀해 주세요."
            } else {
                "이 대화에서 “$quoted”라고 말씀하신 기록은 없습니다."
            }
        }
        if (prior.isEmpty()) return "이 세션에서 아직 처리한 대화가 없습니다."
        // Without a quoted span the claim still has to be checked. Saying "네, 그렇게 말씀하셨습니다"
        // for a request that is not in the transcript would invent conversation history.
        val claimed = claimTerms(raw)
        val supported = claimed.isNotEmpty() && prior.any { entry ->
            entry.role == ModelConversationRole.USER && claimed.all(entry.text::contains)
        }
        return if (supported) {
            "네, 이 대화에서 ${claimed.joinToString(" ")} 관련 요청을 하셨습니다. 다시 실행하려면 말씀해 주세요."
        } else {
            "이 대화에서 그런 요청을 하신 기록은 없습니다."
        }
    }

    /**
     * The stored fact this question is asking about, phrased as an answer, or null.
     *
     * Matches on the attribute the question names — 회사, 직책, 이름 — against the key the fact was
     * stored under. No sentence is matched and no value is guessed: if nothing was stored under that
     * key, this returns null and the ordinary recall path runs.
     */
    private fun rememberedFactAnswer(raw: String, context: TurnContext): String? {
        val asked = FACT_ATTRIBUTES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(raw) }
            ?: return null
        val (_, key) = asked
        val fact = context.memory.confirmedFacts.lastOrNull { it.key == key } ?: return null
        return "이 대화에서 ${fact.content} 라고 말씀하셨습니다."
    }

    /** Attribute wording mapped to the key a stated fact is stored under. */
    private val FACT_ATTRIBUTES: List<Pair<Regex, String>> = listOf(
        // 소속, not only 소속사: "전에 알려준 소속이 어디였지?" asks for the same stored fact, and
        // requiring the longer word answered a question the session could already answer with
        // "그런 기록은 없습니다".
        Regex("회사|직장|소속") to "user.company",
        Regex("직책|직함|직급") to "user.title",
        Regex("부서|팀") to "user.department",
        Regex("이름|성함") to "user.name",
        Regex("이메일|메일\\s*주소") to "user.email",
        Regex("전화번호|휴대폰|핸드폰|연락처") to "user.phone",
        Regex("주소") to "user.address",
    )

    /**
     * The conversation as it stood *before* this question.
     *
     * The current user message is already in the transcript by the time routing runs, and it quotes
     * the very request being asked about — so checking the claim against the full transcript would
     * always find the question itself and confirm every recall, true or not.
     */
    private fun priorTranscript(raw: String, context: TurnContext): List<TranscriptEntry> {
        val last = context.transcript.lastOrNull() ?: return context.transcript
        val isThisTurn = last.role == ModelConversationRole.USER && last.text.trim() == raw
        return if (isThisTurn) context.transcript.dropLast(1) else context.transcript
    }

    /** Content words of the recalled claim, with the recall grammar itself stripped out. */
    private fun claimTerms(raw: String): List<String> {
        val head = raw.split(*RECALL_SPLIT_MARKERS).firstOrNull().orEmpty()
        return CLAIM_TERM_REGEX.findAll(head)
            .map { it.value }
            .filterNot { it in RECALL_STOP_WORDS }
            .filter { it.length >= 2 }
            .distinct()
            .take(4)
            .toList()
    }

    private fun capabilityVeto(raw: String): TurnRoutePlan? = when {
        realSendMarkers.containsMatchIn(raw) -> TurnRoutePlan.Unsupported(
            "이메일이나 문자를 직접 전송할 수 없습니다. 작성 화면만 열 수 있습니다.",
        )
        deleteMarkers.containsMatchIn(raw) && cardObjectMarkers.any(raw::contains) ->
            TurnRoutePlan.Unsupported("명함 삭제는 지원하지 않습니다.")
        phoneCallMarkers.containsMatchIn(raw) ->
            TurnRoutePlan.Unsupported("전화 걸기는 지원하지 않습니다.")
        else -> null
    }

    private fun answerFromHistory(raw: String, context: TurnContext): TurnRoutePlan? {
        if (failureQuestionMarkers.any(raw::contains)) {
            val failed = context.memory.actions.lastOrNull {
                it.status == TrackedActionStatus.FAILED || it.status == TrackedActionStatus.CANCELLED
            } ?: return TurnRoutePlan.AnswerFromHistory("이 대화에서 실패한 요청은 없습니다.")
            val reason = failed.detailKo ?: "구체적인 실패 이유를 기록하지 못했습니다."
            return TurnRoutePlan.AnswerFromHistory("‘${failed.request}’ 요청이 실패했습니다. 이유: $reason")
        }
        val people = knownPeople(context)
        val descriptor = descriptorTerms(raw)
        if (descriptor.isNotEmpty()) {
            val matched = people.filter { candidate ->
                descriptor.any { term ->
                    candidate.company?.contains(term) == true ||
                        candidate.title?.contains(term) == true ||
                        candidate.name.contains(term)
                }
            }
            return TurnRoutePlan.AnswerFromHistory(
                if (matched.isEmpty()) {
                    "이 대화에서는 ${descriptor.joinToString(", ")} 조건에 맞는 사람을 언급한 적이 없습니다."
                } else {
                    "이 대화에서 언급된 대상은 ${matched.joinToString(", ") { it.distinguishingLabel }}입니다."
                },
            )
        }
        if (historyQuestionMarkers.any(raw::contains)) {
            if (people.isNotEmpty()) {
                return TurnRoutePlan.AnswerFromHistory(
                    "이 대화에서 확인한 연락처는 ${people.joinToString(", ") { it.distinguishingLabel }}입니다.",
                )
            }
            val lastAssistant = context.transcript.lastOrNull { it.role == ModelConversationRole.ASSISTANT }
            return TurnRoutePlan.AnswerFromHistory(
                lastAssistant?.let { "직전 대화에서 이렇게 안내했습니다: ${it.text.singleLine()}" }
                    ?: "이 세션에서 아직 처리한 대화가 없습니다.",
            )
        }
        return null
    }

    private fun knownPeople(context: TurnContext): List<ContactCandidate> {
        val selected = context.memory.selectedContact
        val fromSelected = selected?.let {
            listOf(ContactCandidate(it.cardId, it.name, it.company, it.title))
        }.orEmpty()
        val fromMentions = context.memory.activeMentions
            .sortedBy(ContactMention::order)
            .map { ContactCandidate(it.cardId, it.name, it.company, it.title) }
        return (fromSelected + context.memory.candidateContacts + fromMentions)
            .distinctBy { it.cardId }
    }

    /** Words the user is filtering by, e.g. "IT 종사자" or "개발자". Stop words are dropped. */
    private fun descriptorTerms(raw: String): List<String> = DESCRIPTOR_TERMS.filter(raw::contains)

    private fun mentionsKnownName(raw: String, context: TurnContext): Boolean {
        val squeezed = raw.noSpaces()
        return knownPeople(context).any { squeezed.contains(it.name.noSpaces()) }
    }

    private fun ordinalIndex(raw: String, squeezed: String): Int? = when {
        squeezed.contains("첫번째") || raw.contains("1번") -> 0
        squeezed.contains("두번째") || raw.contains("2번") -> 1
        squeezed.contains("세번째") || raw.contains("3번") -> 2
        else -> null
    }

    /**
     * What the turn is, for sentences the rules pass through to the model unchanged.
     *
     * The clock is asked first because [CurrentDateTimeIntent] already knows to stand down when a
     * calendar, message or card request owns the time expression; asking it first therefore costs
     * nothing and keeps one detector, rather than an ordering convention, responsible for the rule.
     */
    private fun actOf(raw: String): DialogueAct {
        // Keyword tests read the name-masked utterance. Asked of the raw sentence they cannot tell
        // 문자현 (a person) from 문자 (send an SMS), or 서수정 (a person) from 수정 (edit a card), and
        // both were routed as the action their name happens to spell.
        val masked = PersonNameMask.maskNames(raw)
        return when {
        CurrentDateTimeIntent.isDirectQuery(raw) -> DialogueAct.DATETIME_QUERY
        ActionVocabulary.COMPOSE.any { masked.contains(it, ignoreCase = true) } ->
            DialogueAct.ACTION_COMPOSE
        ActionVocabulary.CALENDAR.any(masked::contains) -> DialogueAct.ACTION_CALENDAR
        CardUpdateIntent.hasUpdateVerb(raw) -> DialogueAct.ACTION_UPDATE
        // A read verb, not only a store-search verb: "명함 보여줘" and "연락처 확인해줘" are the same
        // request as "명함 검색해줘", and labelling only the third one lost the route for the rest.
        ContactReadIntent.isCardRead(raw) -> DialogueAct.CONTACT_SEARCH
        else -> DialogueAct.OTHER
        }
    }

    /**
     * True when the sentence asks what a *remembered contact's card* says.
     *
     * Korean recall grammar and attribute questions overlap — "…라고 했지?" ends both "내가 메일
     * 보내달라고 했지?" and "그 사람 회사가 어디라고 했지?" — but only the first is about the
     * conversation. The second names a field of somebody this session already verified, so it is
     * answerable from a fresh card read and must not be diverted into a transcript answer.
     */
    /**
     * Is this asking the conversation to repeat something, rather than asking about the world?
     *
     * Structural, not a list of sentences: a word that points backwards plus a question that asks
     * for something back. That pairing is what separates "아까 그 사람 회사가 어디라고 했지?" — answer
     * it from what was said — from "그 사람 회사가 어디야?", which is a question about the card.
     *
     * A recall is deliberately allowed to be reported speech; "…라고 했지?" is reported speech and is
     * exactly the shape being recognised. What it must not be is a quotation being discussed, a
     * hypothetical, or a negation, because those are not requests to answer at all.
     */
    private fun isHistoricalRecall(raw: String): Boolean {
        if (QUOTE_REGEX.containsMatchIn(raw)) return false
        if (ReportedSpeechIntent.HYPOTHETICAL.containsMatchIn(raw)) return false
        if (ReportedSpeechIntent.NEGATION.containsMatchIn(raw)) return false
        if (!recallQuestionMarkers.any(raw::contains)) return false
        // Either the sentence points back in time, or it names the speaker of the earlier statement.
        // "내가 다닌다고 한 회사가 어디였지?" carries no 아까 or 전에; what makes it a recall is that
        // the user is quoting themselves. Requiring the backward word alone lost exactly that shape.
        return pastReferenceMarkers.any(raw::contains) || REQUESTER_SUBJECT_REGEX.containsMatchIn(raw)
    }

    /** What an explicit "do it now" request is asking for. */
    private enum class FreshLookup { SEARCH, DETAIL }

    /** Asking beats guessing: a fresh lookup with nobody to look up is a question, not a broad search. */
    private fun noKnownTarget(): Decision = Decision(
        DialogueAct.CLARIFICATION_REQUIRED,
        TurnRoutePlan.Clarify(
            "어떤 분의 정보를 확인할까요? 이 대화에서 확인된 연락처가 없습니다.",
            ClarifyReason.NO_KNOWN_TARGET,
        ),
    )

    /**
     * Reads a request that explicitly asks for current information.
     *
     * Both halves are required. Freshness alone is not a lookup, and a bare verb is the ordinary
     * request the rest of the router already handles — narrowing on the pair is what keeps "지금
     * 시간 알려줘" and "메일 내용 확인 부탁드립니다" out of here.
     *
     * A search verb wins over a read verb, because "현재 소속을 검색해서 확인해줘" asks to go and look,
     * and the confirmation is what the user will do with the result.
     */
    private fun freshLookupIntent(
        raw: String,
        squeezedForKeywords: String,
        performsARequest: Boolean,
    ): FreshLookup? {
        // Sentences that only mention looking something up: reported, quoted, refused or supposed.
        if (!performsARequest) return null
        if (QUOTE_REGEX.containsMatchIn(raw)) return null
        if (refusalMarkers.containsMatchIn(raw)) return null
        if (conditionalQuestionMarkers.containsMatchIn(raw)) return null
        if (isHistoricalRecall(raw)) return null
        // A question scoped to the conversation asks about the record, not about the world now:
        // "지금까지 한 대화 기록에서 IT 종사자 찾아줘" contains 지금 and 찾아 and is neither.
        if (conversationScopeMarkers.any(raw::contains)) return null
        // A request to *do* something keeps its own route. Freshness words live inside ordinary
        // actions — "그분에게 지금 바로 확인 부탁드린다고 메일 작성해줘" is a compose, not a card read.
        if (actionMarkers.any { squeezedForKeywords.contains(it, ignoreCase = true) }) return null

        if (!freshnessMarkers.any(raw::contains)) return null
        if (freshSearchPredicates.any(raw::contains)) return FreshLookup.SEARCH
        if (freshDetailPredicates.any(raw::contains)) return FreshLookup.DETAIL
        if (freshDetailWeakPredicates.any(raw::contains) && cardObjectMarkers.any(raw::contains)) {
            return FreshLookup.DETAIL
        }
        return null
    }

    /** The known person this sentence names, if it names one at all. */
    private fun namedKnownPerson(raw: String, context: TurnContext): ContactCandidate? {
        val squeezed = raw.noSpaces()
        return knownPeople(context).firstOrNull { squeezed.contains(it.name.noSpaces()) }
    }

    private fun asksAboutRememberedContact(raw: String, context: TurnContext): Boolean {
        // Reported speech is always a recall. A quoted span *is* the thing being recalled, and it
        // routinely contains the person's name, so reading the quote as a reference would re-run the
        // very instruction the user is only asking about.
        if (QUOTE_REGEX.containsMatchIn(raw)) return false
        // "내가 …했지?" names the speaker of a past request, so the sentence is about the request.
        if (REQUESTER_SUBJECT_REGEX.containsMatchIn(raw)) return false
        val focus = context.memory.selectedContact?.takeIf { it.isActionable } ?: return false
        val squeezed = raw.noSpaces()
        val refersToFocus = ContactAnaphora.isPresent(raw) ||
            (focus.name.isNotBlank() && squeezed.contains(focus.name.noSpaces())) ||
            attributeStarters.any { squeezed.startsWith(it.noSpaces()) }
        return refersToFocus && requestedFields(raw) != null
    }

    /** A request aimed at an app this agent has no integration with. */
    private fun externalIntegration(raw: String): TurnRoutePlan? {
        val app = ExternalIntegrationIntent.namedApp(raw) ?: return null
        return TurnRoutePlan.GeneralInformation(
            "‘$app’ 연동은 지원하지 않습니다. 이 에이전트는 저장된 명함 검색·조회·수정과 " +
                "메일/문자 작성 화면 열기, 일정 작성 화면 열기만 처리합니다.",
        )
    }

    private fun rewrite(raw: String, name: String): String {
        var resolved = raw
            .replace("그 회사", "$name 회사")
            .replace("그 사람에게", "${name}에게")
            .replace("그에게", "${name}에게")
            // A card-object anaphor keeps its object. Collapsing "그 명함 수정해줘" to "곽서린
            // 수정해줘" would drop the very word that tells the downstream parser this is a card edit.
            .replace("그 연락처", "$name 연락처")
            .replace("그 명함", "$name 명함")
            .replace("해당 연락처", "$name 연락처")
            .replace("해당 명함", "$name 명함")
        referenceSearchMarkers.forEach { marker ->
            resolved = resolved.replace("$marker 사람", name).replace("$marker 분", name)
        }
        pronounMarkers.forEach { pronoun -> resolved = resolved.replace(pronoun, name) }
        if (resolved != raw) return resolved
        val startsWithOwnedAttribute =
            attributeStarters.any { raw.noSpaces().startsWith(it.noSpaces()) } && requestedFields(raw) != null
        return if (startsWithOwnedAttribute) "$name $raw" else raw
    }

    private fun String.noSpaces(): String = replace(Regex("\\s+"), "")

    private fun String.singleLine(): String = replace(Regex("\\s+"), " ").trim()

    private val QUOTE_REGEX = Regex("[‘“\"']([^’”\"']{2,80})[’”\"']")
    /** The speaker of a recalled request. Its presence makes the sentence about the request. */
    private val REQUESTER_SUBJECT_REGEX = Regex("내가|제가|우리가|너한테|너에게")
    private val DESCRIPTOR_TERMS = listOf(
        "IT", "it", "개발", "개발자", "디자이너", "영업", "마케팅", "투자", "대표", "인사", "연구",
    )
    private val REPLACEMENT_REGEX = Regex("([^,.]{2,20}?)\\s*(?:말고|말구|이 아니라|가 아니라|아니라|아니고)\\s+")
    private val CONTACT_VALUE_REGEX = Regex(
        """[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|(?<!\d)0\d{1,2}[- ]?\d{3,4}[- ]?\d{4}(?!\d)""",
    )
    private val EXECUTION_VERB_REGEX = Regex(
        "(작성|발송|전송|생성|등록|추가)\\s*(?:해\\s*줘|해주세요|해라|하자)|" +
            "(써\\s*줘|보내\\s*줘|만들어\\s*줘|열어\\s*줘|잡아\\s*줘)",
    )
    private val CLAIM_TERM_REGEX = Regex("[\\p{IsHangul}A-Za-z0-9]{2,}")
    private val RECALL_SPLIT_MARKERS = arrayOf(
        "라고 말했", "라고 했", "고 말했", "달라고", "라고 요청", "고 요청", "말한 적", "얘기했",
    )
    private val RECALL_STOP_WORDS = setOf(
        "내가", "제가", "우리", "그거", "그것", "아까", "방금", "이전", "전에", "지금",
    )
}
