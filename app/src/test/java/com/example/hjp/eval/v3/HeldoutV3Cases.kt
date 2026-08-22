package com.example.hjp.eval.v3

import com.example.hjp.eval.v3.HeldoutV3Roster as R
import com.example.hjp.eval.v3.V3Categories as C
import com.example.hjp.eval.v3.V3Tools.CALENDAR
import com.example.hjp.eval.v3.V3Tools.COMPOSE
import com.example.hjp.eval.v3.V3Tools.GET
import com.example.hjp.eval.v3.V3Tools.NOW
import com.example.hjp.eval.v3.V3Tools.SEARCH
import com.example.hjp.eval.v3.V3Tools.UPDATE
import com.hjp.agent.contract.DialogueAct as A
import com.hjp.agent.contract.TurnOutcomeType as O
import com.hjp.tool.contact.BusinessCardRecord
import java.time.LocalDate

/**
 * Held-out v3.
 *
 * Written after the production source was locked and SHA-recorded in
 * `pre_device_v3/freeze/production_freeze_v3.json`, and scored exactly once. Like v1 and v2 it is a
 * *post-implementation frozen* held-out set, not a blinded one: the same assistant wrote the
 * implementation and this dataset. What controls contamination is the lock, the fact that every
 * expectation is derived from the behaviour contract rather than from a trial run, and the rule that
 * nothing here is edited after results are seen.
 *
 * It is not a rewrite of v2's failures. The four collisions probed here (문자영, 안수정, 조회린,
 * 김일정) are different words colliding with different vocabulary from v2's two, the clarification
 * group covers both channels and both directions, and the datetime group probes the canonical form
 * the real model actually emits. Every utterance is new: validation rejects the suite if any of them
 * has appeared in the visible suite, the known-regression suite, held-out v1 or held-out v2.
 */
object HeldoutV3Cases {

    /**
     * Chooses surface forms only: which search phrasing and which mail body a scenario uses.
     * People, ids, conversation shapes and every expected value are static, hand-written fixtures.
     */
    const val SEED = 20260811L

    /** Relative dates resolve through the clock, so the day is pinned and validation enforces it. */
    val REFERENCE_DATE: LocalDate = LocalDate.of(2026, 8, 10)
    const val TIMEZONE = "Asia/Seoul"
    const val LOCALE = "ko-KR"

    private class Rng(seed: Long) {
        private var state = seed
        fun next(bound: Int): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toInt() and 0x7fffffff) % bound
        }
    }

    private val SEARCH_PHRASINGS = listOf(
        "%s 명함 어디 있지 찾아줘.",
        "%s 연락처 좀 띄워줘.",
        "%s 명함 한번 조회해줘.",
        "%s 연락처 화면에 보여줘.",
        "%s 명함 검색 부탁해.",
    )
    private val MAIL_BODIES = listOf(
        "제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.",
        "제목은 일정 조율, 내용은 가능한 시간 알려주세요 라고 메일 작성해 줘.",
        "제목은 사양 확인, 내용은 규격서 회신 부탁드립니다 라고 메일 작성해주세요.",
        "제목은 방문 요청, 내용은 현장 확인 부탁드립니다 라고 메일 작성해줘.",
    )
    private val SMS_BODIES = listOf(
        "도착했다고 문자 작성해줘.",
        "서류 접수됐다고 문자 작성해 줘.",
        "확인 부탁한다고 문자 작성해주세요.",
    )

    // ---- turn builders ---------------------------------------------------------------------------

    private fun search(card: BusinessCardRecord, phrasing: Int) = V3Turn(
        user = SEARCH_PHRASINGS[phrasing % SEARCH_PHRASINGS.size].format(card.name),
        act = A.CONTACT_SEARCH,
        outcome = O.CONTACT_SELECTED,
        tools = listOf(SEARCH),
        forbidden = setOf(COMPOSE, CALENDAR, UPDATE, GET, NOW),
        argPatterns = mapOf(SEARCH to mapOf("query" to "(?s).*${Regex.escape(card.name)}.*")),
        expectedTargetCardId = card.id,
        candidateIds = listOf(card.id),
        answerContains = listOf("명함 검색 결과입니다", card.name, card.company),
    )

    private fun ambiguousSearch(name: String, a: BusinessCardRecord, b: BusinessCardRecord) = V3Turn(
        user = "$name 명함 어디 있지 찾아줘.",
        act = A.CONTACT_SEARCH,
        outcome = O.CONTACT_SELECTED,
        tools = listOf(SEARCH),
        forbidden = setOf(COMPOSE, CALENDAR, UPDATE, GET, NOW),
        argPatterns = mapOf(SEARCH to mapOf("query" to "(?s).*${Regex.escape(name)}.*")),
        expectNoTarget = true,
        candidateIds = listOf(a.id, b.id),
        answerContains = listOf(a.company, b.company),
    )

    private fun zeroResultSearch(name: String) = V3Turn(
        user = "$name 명함 어디 있지 찾아줘.",
        act = A.CONTACT_SEARCH,
        outcome = O.CONTACT_SELECTED,
        tools = listOf(SEARCH),
        forbidden = setOf(COMPOSE, CALENDAR, UPDATE, GET, NOW),
        argPatterns = mapOf(SEARCH to mapOf("query" to "(?s).*${Regex.escape(name)}.*")),
        expectNoTarget = true,
        candidateIds = emptyList(),
        answerContains = listOf("찾지 못했습니다"),
    )

    private fun composeMail(prefix: String, card: BusinessCardRecord, body: Int) = V3Turn(
        user = "$prefix ${MAIL_BODIES[body % MAIL_BODIES.size]}",
        act = A.ACTION_COMPOSE,
        outcome = O.COMPOSE_OPENED,
        tools = listOf(GET, COMPOSE),
        forbidden = setOf(SEARCH, CALENDAR, UPDATE, NOW),
        args = mapOf(
            GET to mapOf("card_id" to card.id),
            COMPOSE to mapOf("channel" to "email", "to" to card.email),
        ),
        argPatterns = mapOf(COMPOSE to mapOf("body" to "(?s).{4,}")),
        sideEffects = 1,
        composeTo = card.email,
        expectedTargetCardId = card.id,
        answerContains = listOf("메일 작성 화면을 열었습니다"),
        answerExcludes = listOf("전송했습니다", "발송했습니다"),
    )

    private fun composeSms(prefix: String, card: BusinessCardRecord, body: Int) = V3Turn(
        user = "$prefix ${SMS_BODIES[body % SMS_BODIES.size]}",
        act = A.ACTION_COMPOSE,
        outcome = O.COMPOSE_OPENED,
        tools = listOf(GET, COMPOSE),
        forbidden = setOf(SEARCH, CALENDAR, UPDATE, NOW),
        args = mapOf(
            GET to mapOf("card_id" to card.id),
            COMPOSE to mapOf("channel" to "sms", "to" to card.mobile),
        ),
        argPatterns = mapOf(COMPOSE to mapOf("body" to "(?s).{2,}")),
        sideEffects = 1,
        composeTo = card.mobile,
        expectedTargetCardId = card.id,
        answerContains = listOf("문자 작성 화면을 열었습니다"),
        answerExcludes = listOf("전송했습니다", "발송했습니다"),
    )

    private fun bareCalendar(
        text: String,
        startTime: String,
        title: String,
        stillInFocus: String? = null,
        forbiddenThisTurn: List<String> = emptyList(),
    ) = V3Turn(
        user = text,
        act = A.ACTION_CALENDAR,
        outcome = O.CALENDAR_OPENED,
        tools = listOf(CALENDAR),
        forbidden = setOf(SEARCH, GET, COMPOSE, UPDATE, NOW),
        args = mapOf(CALENDAR to mapOf("start_time" to startTime, "title" to title)),
        sideEffects = 1,
        calendarAttendees = emptyList(),
        expectedTargetCardId = stillInFocus,
        forbiddenValues = forbiddenThisTurn,
        answerContains = listOf("캘린더 작성 화면을 열었습니다"),
        answerExcludes = listOf("저장했습니다"),
    )

    private fun attendeeCalendar(
        text: String,
        card: BusinessCardRecord,
        startTime: String,
        title: String,
    ) = V3Turn(
        user = text,
        act = A.ACTION_CALENDAR,
        outcome = O.CALENDAR_OPENED,
        tools = listOf(GET, CALENDAR),
        forbidden = setOf(SEARCH, COMPOSE, UPDATE, NOW),
        args = mapOf(
            GET to mapOf("card_id" to card.id),
            CALENDAR to mapOf("start_time" to startTime, "title" to title),
        ),
        sideEffects = 1,
        calendarAttendees = listOf(card.email),
        expectedTargetCardId = card.id,
        answerContains = listOf("캘린더 작성 화면을 열었습니다"),
    )

    private fun relativeCalendar(text: String, startTime: String, title: String) = V3Turn(
        user = text,
        act = A.ACTION_CALENDAR,
        outcome = O.CALENDAR_OPENED,
        tools = listOf(NOW, CALENDAR),
        forbidden = setOf(SEARCH, GET, COMPOSE, UPDATE),
        args = mapOf(CALENDAR to mapOf("start_time" to startTime, "title" to title)),
        sideEffects = 1,
        calendarAttendees = emptyList(),
        answerContains = listOf("캘린더 작성 화면을 열었습니다"),
    )

    private fun detail(prefix: String, question: String, card: BusinessCardRecord, value: String) = V3Turn(
        user = "$prefix $question",
        act = A.CONTACT_DETAIL,
        outcome = O.CONTACT_DETAIL_SHOWN,
        tools = listOf(GET),
        forbidden = setOf(SEARCH, COMPOSE, CALENDAR, UPDATE, NOW),
        args = mapOf(GET to mapOf("card_id" to card.id)),
        expectedTargetCardId = card.id,
        answerContains = listOf("${card.name} 명함 정보입니다", value),
    )

    private fun updateMemo(prefix: String, card: BusinessCardRecord, value: String) = V3Turn(
        user = "$prefix 메모를 ${value}로 수정해줘.",
        act = A.ACTION_UPDATE,
        outcome = O.UPDATE_COMPLETED,
        tools = listOf(GET, UPDATE),
        forbidden = setOf(SEARCH, COMPOSE, CALENDAR, NOW),
        args = mapOf(GET to mapOf("card_id" to card.id), UPDATE to mapOf("card_id" to card.id)),
        sideEffects = 1,
        expectedTargetCardId = card.id,
        answerContains = listOf("명함을 수정했습니다"),
        answerExcludes = listOf("완료하지 못했습니다"),
    )

    private fun smallTalk(text: String, focus: String? = null) = V3Turn(
        user = text,
        act = A.OTHER,
        outcome = O.GENERAL_INFORMATION,
        forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW),
        expectedTargetCardId = focus,
        answerContains = listOf("명함 검색"),
        answerExcludes = listOf("열었습니다", "수정했습니다"),
    )

    private fun clock(text: String) = V3Turn(
        user = text,
        act = A.DATETIME_QUERY,
        outcome = O.GENERAL_INFORMATION,
        tools = listOf(NOW),
        forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE),
        answerContains = listOf("현재 날짜와 시각입니다", "시간대"),
    )

    private fun information(text: String, topic: String) = V3Turn(
        user = text,
        act = A.GENERAL_INFORMATION,
        outcome = O.GENERAL_INFORMATION,
        forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW),
        generalInformation = true,
        semanticTopic = topic,
        answerContains = listOf("일반 지식"),
    )

    private fun vetoed(text: String, refusal: String) = V3Turn(
        user = text,
        act = A.UNSUPPORTED,
        outcome = O.UNSUPPORTED,
        forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW),
        unsupportedRequest = true,
        answerContains = listOf(refusal),
    )

    private fun externalApp(text: String, app: String) = V3Turn(
        user = text,
        act = A.OTHER,
        outcome = O.GENERAL_INFORMATION,
        forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW),
        unsupportedRequest = true,
        answerContains = listOf(app, "지원하지 않습니다"),
    )

    private fun clarifyNoTarget(text: String) = V3Turn(
        user = text,
        act = A.CLARIFICATION_REQUIRED,
        outcome = O.CLARIFICATION_REQUIRED,
        forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW),
        expectNoTarget = true,
        answerContains = listOf("어떤 분을 말씀하시는지"),
    )

    private fun clarifyAmbiguous(text: String, a: BusinessCardRecord, b: BusinessCardRecord) = V3Turn(
        user = text,
        act = A.CLARIFICATION_REQUIRED,
        outcome = O.CLARIFICATION_REQUIRED,
        forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW),
        expectNoTarget = true,
        answerContains = listOf("대상이 여러 명입니다", a.company, b.company),
    )

    /** The defect this suite exists to pin: a request with no recipient must ask who. */
    private fun missingRecipient(text: String, hint: String, focus: String?, forbiddenValue: String?) = V3Turn(
        user = text,
        act = A.ACTION_COMPOSE,
        outcome = O.GENERAL_INFORMATION,
        forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW),
        missingRequiredSlot = true,
        expectedTargetCardId = focus,
        forbiddenValues = listOfNotNull(forbiddenValue),
        answerContains = listOf(hint),
        answerExcludes = listOf("완료하지 못했습니다", "다시 시도해"),
    )

    private fun missingCalendarTime(text: String, focus: String?) = V3Turn(
        user = text,
        act = A.ACTION_CALENDAR,
        outcome = O.GENERAL_INFORMATION,
        forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW),
        missingRequiredSlot = true,
        expectedTargetCardId = focus,
        answerContains = listOf("일정 시작 날짜와 시각"),
    )

    private fun missingUpdateField(prefix: String, focus: String) = V3Turn(
        user = "$prefix 명함 좀 고쳐줘.",
        act = A.ACTION_UPDATE,
        outcome = O.GENERAL_INFORMATION,
        forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW),
        missingRequiredSlot = true,
        expectedTargetCardId = focus,
        answerContains = listOf("수정하거나 비울 명함 필드"),
    )

    private fun recallDenied(text: String) = V3Turn(
        user = text,
        act = A.QUOTED_RECALL,
        outcome = O.ANSWER_FROM_HISTORY,
        forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW),
        quotedRecall = true,
        answerContains = listOf("기록은 없습니다"),
    )

    private fun ordinalPick(text: String, card: BusinessCardRecord) = V3Turn(
        user = text,
        act = A.CONTACT_SELECTION,
        outcome = O.CONTACT_DETAIL_SHOWN,
        tools = listOf(GET),
        forbidden = setOf(SEARCH, COMPOSE, CALENDAR, UPDATE, NOW),
        args = mapOf(GET to mapOf("card_id" to card.id)),
        expectedTargetCardId = card.id,
        answerContains = listOf("${card.name} 명함 정보입니다", card.company),
    )

    // ---- date literals ------------------------------------------------------------------------
    private fun iso(date: LocalDate, time: String) =
        "%04d-%02d-%02dT%s".format(date.year, date.monthValue, date.dayOfMonth, time)

    private val TOMORROW_1400 = iso(REFERENCE_DATE.plusDays(1), "14:00")
    private val TODAY_0800 = iso(REFERENCE_DATE, "08:00")
    private val DAY_AFTER_1100 = iso(REFERENCE_DATE.plusDays(2), "11:00")

    val SCENARIOS: List<V3Scenario> by lazy { build() }

    private fun build(): List<V3Scenario> {
        val rng = Rng(SEED)
        fun p() = rng.next(SEARCH_PHRASINGS.size)
        fun m() = rng.next(MAIL_BODIES.size)

        val out = mutableListOf<V3Scenario>()
        fun add(
            id: String,
            category: String,
            intent: String,
            turns: List<V3Turn>,
            cards: List<BusinessCardRecord> = R.ALL,
            tags: List<String> = emptyList(),
            searchFailure: Boolean = false,
            neverAnywhere: List<String> = emptyList(),
        ) {
            out += V3Scenario(id, category, tags, turns, cards, searchFailure, true, neverAnywhere, intent)
        }

        // ===== name / action-word collision ==========================================================
        R.COLLIDING.forEachIndexed { index, card ->
            add(
                "v3_collision_lookup_${card.id}", C.NAME_COLLISION,
                "a person whose name spells one of the agent's own verbs is still a person; the lookup " +
                    "has to run and the follow-up has to have a target",
                listOf(
                    search(card, index),
                    detail("그 사람", "회사 이름이 어떻게 되나요?", card, card.company),
                ),
                tags = listOf("collision"),
            )
        }
        add(
            "v3_collision_control_company_only", C.NAME_COLLISION,
            "the control: the calendar word is on the card, never in what the user types, so this " +
                "isolates the collision to the utterance",
            listOf(
                search(R.CONTROL_MEETING_COMPANY, 0),
                composeMail("그 사람에게", R.CONTROL_MEETING_COMPANY, 0),
            ),
        )
        add(
            "v3_collision_name_then_real_command", C.NAME_COLLISION,
            "masking a name must not disarm the same word used as a command: 문자영 is a person and " +
                "문자 작성 is still an SMS",
            listOf(
                search(R.JAYEONG_SMS, 2),
                composeSms("그분에게", R.JAYEONG_SMS, 0),
                updateMemo("그 사람", R.JAYEONG_SMS, "홍보검토"),
            ),
        )
        add(
            "v3_collision_named_action", C.NAME_COLLISION,
            "naming a colliding person outright still routes as the action, not as their name",
            listOf(
                search(R.SUJEONG_EDIT, 3),
                updateMemo("그분", R.SUJEONG_EDIT, "구매확정"),
            ),
        )

        // ===== focus vs turn target ==================================================================
        add(
            "v3_focus_bare_calendar_after_search", C.FOCUS_TARGET,
            "a schedule naming no attendee must not acquire the person in focus",
            listOf(
                search(R.NARAE, p()),
                bareCalendar("2027년 3월 12일 오후 4시 설비 점검 일정 만들어줘.", "2027-03-12T16:00", "일정",
                    R.NARAE.id, listOf(R.NARAE.email)),
            ),
        )
        add(
            "v3_focus_bare_calendar_after_compose", C.FOCUS_TARGET,
            "the same rule right after a compose, which is where the value is freshest in memory",
            listOf(
                search(R.JUNSEO, p()),
                composeMail("그 사람에게", R.JUNSEO, m()),
                bareCalendar("2027년 4월 2일 오전 9시 내부 점검 일정 만들어줘.", "2027-04-02T09:00", "일정",
                    R.JUNSEO.id, listOf(R.JUNSEO.email)),
            ),
        )
        add(
            "v3_focus_bare_calendar_after_update", C.FOCUS_TARGET,
            "a card write also leaves focus behind and must not bind the next schedule",
            listOf(
                search(R.SOLBIN, p()),
                updateMemo("그 사람", R.SOLBIN, "공정검토"),
                bareCalendar("2027년 5월 19일 오후 1시 공정 점검 일정 만들어줘.", "2027-05-19T13:00", "일정",
                    R.SOLBIN.id, listOf(R.SOLBIN.email)),
            ),
        )
        add(
            "v3_focus_reference_still_binds", C.FOCUS_TARGET,
            "the other direction: an explicit reference must still reach the person in focus",
            listOf(
                search(R.YERAM, p()),
                bareCalendar("2027년 6월 4일 오전 11시 편집 점검 일정 만들어줘.", "2027-06-04T11:00", "일정",
                    R.YERAM.id, listOf(R.YERAM.email)),
                attendeeCalendar("그 사람과 2027년 7월 1일 오후 2시 편집 협의 일정 만들어줘.", R.YERAM,
                    "2027-07-01T14:00", "봉예람 일정"),
            ),
        )
        add(
            "v3_focus_clock_between", C.FOCUS_TARGET,
            "a clock lookup between the search and the schedule is not an attendee either",
            listOf(
                search(R.TAEUL, p()),
                clock("지금 몇 시인지 확인 좀."),
                bareCalendar("2027년 8월 7일 오후 5시 배송 점검 일정 만들어줘.", "2027-08-07T17:00", "일정",
                    R.TAEUL.id, listOf(R.TAEUL.email)),
            ),
        )

        // ===== required slot and clarification =======================================================
        add(
            "v3_slot_mail_recipient_symmetry", C.SLOT,
            "a mail with no recipient must ask who, not address the person in focus",
            listOf(
                search(R.MIROO, p()),
                missingRecipient("메일 하나 작성해줘.", "메일 수신자", R.MIROO.id, R.MIROO.email),
                composeMail("그 사람에게", R.MIROO, m()),
            ),
        )
        add(
            "v3_slot_sms_recipient_symmetry", C.SLOT,
            "the same request on the SMS channel must behave identically",
            listOf(
                search(R.GANGYU, p()),
                missingRecipient("문자 하나 작성해줘.", "문자 수신자", R.GANGYU.id, R.GANGYU.mobile),
                composeSms("그분에게", R.GANGYU, 1),
            ),
        )
        add(
            "v3_slot_calendar_time_then_complete", C.SLOT,
            "a schedule with no time asks, and the answer completes it",
            listOf(
                search(R.HYORIN, p()),
                missingCalendarTime("일정 하나 만들어줘.", R.HYORIN.id),
                bareCalendar("2027년 9월 9일 오전 10시 전략 점검 일정 만들어줘.", "2027-09-09T10:00", "일정",
                    R.HYORIN.id),
            ),
        )
        add(
            "v3_slot_update_field_then_complete", C.SLOT,
            "an edit with no field asks, and the answer completes it",
            listOf(
                search(R.NARAE, p()),
                missingUpdateField("그분", R.NARAE.id),
                updateMemo("그분", R.NARAE, "생산확인"),
            ),
        )
        add(
            "v3_slot_no_target_then_search", C.SLOT,
            "an anaphor with an empty session asks; the same sentence works once a target exists",
            listOf(
                clarifyNoTarget("그 사람에게 ${MAIL_BODIES[0]}"),
                search(R.JUNSEO, p()),
                composeMail("그 사람에게", R.JUNSEO, 0),
            ),
        )

        // ===== contact anaphora ======================================================================
        listOf("그 사람에게", "그분에게", "이 사람에게").forEachIndexed { index, prefix ->
            val card = R.PEOPLE[index + 2]
            add(
                "v3_anaphora_${card.id}", C.ANAPHORA,
                "the address is re-read from the card in the acting turn, never recalled",
                listOf(search(card, p()), composeMail(prefix, card, m())),
            )
        }
        add(
            "v3_anaphora_across_information", C.ANAPHORA,
            "a concept question in the middle must neither act nor disturb the focus",
            listOf(
                search(R.MIROO, p()),
                information("명함 정리 요령이 궁금해.", "요령"),
                composeMail("그분에게", R.MIROO, m()),
            ),
        )

        // ===== target switch and a new name mid-conversation ==========================================
        add(
            "v3_switch_anaphor_follows_newer", C.TARGET_SWITCH,
            "after a second search the anaphor means the newer person",
            listOf(
                search(R.GANGYU, p()),
                search(R.HYORIN, p()),
                composeMail("그 사람에게", R.HYORIN, m()),
            ),
            neverAnywhere = listOf(R.GANGYU.email),
        )
        add(
            "v3_switch_correction", C.TARGET_SWITCH,
            "'A 말고 B' replaces the target and A must not stay selectable",
            listOf(
                search(R.NARAE, p()),
                V3Turn(
                    user = "${R.NARAE.name} 말고 ${R.JUNSEO.name} 명함 어디 있지 찾아줘.",
                    act = A.CORRECTION,
                    outcome = O.CONTACT_SELECTED,
                    tools = listOf(SEARCH),
                    forbidden = setOf(COMPOSE, CALENDAR, UPDATE, GET, NOW),
                    argPatterns = mapOf(SEARCH to mapOf("query" to "(?s).*${Regex.escape(R.JUNSEO.name)}.*")),
                    expectedTargetCardId = R.JUNSEO.id,
                    answerContains = listOf("명함 검색 결과입니다", R.JUNSEO.company),
                    answerExcludes = listOf(R.NARAE.company),
                ),
                composeMail("그 사람에게", R.JUNSEO, m()),
            ),
            neverAnywhere = listOf(R.NARAE.email),
        )
        add(
            "v3_switch_three_targets", C.TARGET_SWITCH,
            "three targets in one session, each reached right after its own search",
            listOf(
                search(R.SOLBIN, p()), composeMail("그 사람에게", R.SOLBIN, m()),
                search(R.YERAM, p()), composeMail("그분에게", R.YERAM, m()),
                search(R.TAEUL, p()), composeMail("그 사람에게", R.TAEUL, m()),
            ),
            tags = listOf("long_range"),
        )
        listOf(R.MIROO to R.GANGYU, R.HYORIN to R.NARAE, R.JUNSEO to R.SOLBIN).forEach { (focus, named) ->
            add(
                "v3_new_name_${focus.id}_to_${named.id}", C.NEW_NAME,
                "naming somebody new overrides the focus without any correction grammar, and the " +
                    "new person has to be looked up rather than assumed",
                listOf(
                    search(focus, p()),
                    V3Turn(
                        user = "${named.name}에게 ${MAIL_BODIES[1]}",
                        act = A.ACTION_COMPOSE,
                        outcome = O.COMPOSE_OPENED,
                        tools = listOf(SEARCH, GET, COMPOSE),
                        forbidden = setOf(CALENDAR, UPDATE, NOW),
                        args = mapOf(
                            GET to mapOf("card_id" to named.id),
                            COMPOSE to mapOf("channel" to "email", "to" to named.email),
                        ),
                        argPatterns = mapOf(
                            SEARCH to mapOf("query" to "(?s).*${Regex.escape(named.name)}.*"),
                            COMPOSE to mapOf("body" to "(?s).{4,}"),
                        ),
                        sideEffects = 1,
                        composeTo = named.email,
                        expectedTargetCardId = named.id,
                        forbiddenValues = listOf(focus.email),
                        answerContains = listOf("메일 작성 화면을 열었습니다"),
                        answerExcludes = listOf("전송했습니다"),
                    ),
                ),
                neverAnywhere = listOf(focus.email),
            )
        }

        // ===== multi-tool continuation ================================================================
        add(
            "v3_chain_search_detail_compose", C.CHAIN,
            "the chain must reach the acting tool, not stop after the lookup",
            listOf(
                search(R.NARAE, p()),
                detail("그 사람", "업종이 무엇인가요?", R.NARAE, R.NARAE.industry),
                composeMail("그분에게", R.NARAE, m()),
            ),
        )
        add(
            "v3_chain_search_detail_calendar", C.CHAIN,
            "the same chain ending in a calendar entry with a verified attendee",
            listOf(
                search(R.JUNSEO, p()),
                detail("그분", "회사 이름이 어떻게 되나요?", R.JUNSEO, R.JUNSEO.company),
                attendeeCalendar("그 사람과 2027년 10월 5일 오후 3시 기획 협의 일정 만들어줘.", R.JUNSEO,
                    "2027-10-05T15:00", "제준서 일정"),
            ),
        )
        add(
            "v3_chain_update_then_read_back", C.CHAIN,
            "write, then read back: the answer has to come from the card, not from the request",
            listOf(
                search(R.SOLBIN, p()),
                updateMemo("그 사람", R.SOLBIN, "재검증"),
                detail("그분", "메모에 뭐라고 적혀 있나요?", R.SOLBIN, "재검증"),
            ),
        )
        add(
            "v3_chain_clock_then_relative_calendar", C.CHAIN,
            "the clock result has to be consumed by the calendar call rather than restated",
            listOf(
                clock("지금 몇 시인지 확인 좀."),
                relativeCalendar("내일 오후 2시 사내 점검 일정 만들어줘.", TOMORROW_1400, "일정"),
            ),
        )
        add(
            "v3_chain_four_tools_one_person", C.CHAIN,
            "four tool-bearing turns on one person without the target drifting",
            listOf(
                search(R.YERAM, p()),
                attendeeCalendar("그 사람과 2027년 11월 8일 오전 9시 편집 협의 일정 만들어줘.", R.YERAM,
                    "2027-11-08T09:00", "봉예람 일정"),
                updateMemo("그분", R.YERAM, "출간준비"),
                composeMail("그 사람에게", R.YERAM, m()),
            ),
        )

        // ===== update ==================================================================================
        listOf(R.TAEUL to "배송확정", R.MIROO to "품질승인", R.GANGYU to "설비점검").forEach { (card, memo) ->
            add(
                "v3_update_${card.id}", C.UPDATE,
                "an edit reached by an anaphor writes to the card this session verified",
                listOf(search(card, p()), updateMemo("그 사람", card, memo)),
            )
        }

        // ===== datetime and canonical dates =============================================================
        add(
            "v3_datetime_phrasings", C.DATETIME,
            "two ordinary ways of asking the clock behave identically",
            listOf(clock("지금 몇 시인지 확인 좀."), clock("오늘 날짜 좀 확인 부탁해.")),
        )
        add(
            "v3_datetime_weekday_and_spacing", C.DATETIME,
            "a weekday question and a spacing variant are still clock questions",
            listOf(clock("오늘 무슨 요일인지 확인 좀."), clock("지금 몇시인지 확인해 줘.")),
        )
        add(
            "v3_datetime_relative_tomorrow_and_today", C.DATETIME,
            "a relative date resolves through the clock before the event is written",
            listOf(
                relativeCalendar("내일 오후 2시 사내 점검 일정 만들어줘.", TOMORROW_1400, "일정"),
                relativeCalendar("오늘 오전 8시 현장 점검 일정 만들어줘.", TODAY_0800, "일정"),
            ),
        )
        add(
            "v3_datetime_day_after_tomorrow", C.DATETIME,
            "모레 is two days out and must not become tomorrow",
            listOf(
                search(R.HYORIN, p()),
                relativeCalendar("모레 오전 11시 전략 점검 일정 만들어줘.", DAY_AFTER_1100, "일정"),
            ),
        )
        add(
            "v3_datetime_absolute_needs_no_clock", C.DATETIME,
            "an absolute date needs no clock lookup, and the stored value is minute precision",
            listOf(
                bareCalendar("2028년 2월 14일 오후 6시 정기 점검 일정 만들어줘.", "2028-02-14T18:00", "일정"),
                bareCalendar("2029년 3월 3일 오전 7시 예비 점검 일정 만들어줘.", "2029-03-03T07:00", "일정"),
            ),
        )

        // ===== general information =======================================================================
        add(
            "v3_information_etiquette", C.INFORMATION,
            "two concept questions that share vocabulary with an action but name no target",
            listOf(
                information("거래처 응대 예절이 궁금해.", "예절"),
                information("보고서 작성 원리가 궁금해.", "원리"),
            ),
        )
        add(
            "v3_information_between_work", C.INFORMATION,
            "a concept question between two contact turns changes nothing about the focus",
            listOf(
                search(R.TAEUL, p()),
                information("물류 용어 뜻이 궁금해.", "뜻"),
                detail("그 사람", "회사 이름이 어떻게 되나요?", R.TAEUL, R.TAEUL.company),
            ),
        )
        add(
            "v3_information_method_and_system", C.INFORMATION,
            "'방법' and '체계' are questions even next to action vocabulary",
            listOf(
                information("연락처 정리하는 방법이 궁금해.", "방법"),
                information("직급 체계가 궁금해.", "체계"),
            ),
        )

        // ===== unsupported ================================================================================
        add(
            "v3_unsupported_real_send", C.UNSUPPORTED,
            "the agent opens screens and must never claim it can send",
            listOf(search(R.MIROO, p()), vetoed("그분에게 메일 지금 바로 전송해줘.", "직접 전송할 수 없습니다")),
        )
        add(
            "v3_unsupported_delete", C.UNSUPPORTED,
            "deleting a card is not a capability and must not be approximated by an edit",
            listOf(search(R.GANGYU, p()), vetoed("그 사람 명함 완전히 삭제해줘.", "명함 삭제는 지원하지 않습니다")),
        )
        add(
            "v3_unsupported_call", C.UNSUPPORTED,
            "placing a call is not a capability and an SMS is not a substitute",
            listOf(search(R.HYORIN, p()), vetoed("그분한테 전화 좀 걸어줘.", "전화 걸기는 지원하지 않습니다")),
        )
        add(
            "v3_unsupported_external_then_recovery", C.UNSUPPORTED,
            "an app with no integration is named as missing, and the next turn still works",
            listOf(
                externalApp("정리한 내용을 팀즈에 올려줘.", "팀즈"),
                search(R.NARAE, p()),
                composeMail("그 사람에게", R.NARAE, m()),
            ),
        )

        // ===== quoted recall ================================================================================
        add(
            "v3_recall_never_requested", C.RECALL,
            "a request that was never made must not be confirmed and must not be executed",
            listOf(search(R.JUNSEO, p()), recallDenied("내가 좀 전에 일정 잡아달라고 했나?")),
        )
        add(
            "v3_recall_after_real_action", C.RECALL,
            "a recall after real work is answered from what happened, not from what might have",
            listOf(
                search(R.SOLBIN, p()),
                composeMail("그 사람에게", R.SOLBIN, m()),
                recallDenied("내가 좀 전에 명함 지워달라고 했나?"),
            ),
        )
        add(
            "v3_recall_vs_attribute_question", C.RECALL,
            "'…라고 했나?' ends both a recall and an attribute question; the subject decides",
            listOf(
                search(R.YERAM, p()),
                detail("그 사람", "회사 이름이 뭐라고 했나?", R.YERAM, R.YERAM.company),
            ),
        )

        // ===== selected contact field ========================================================================
        add(
            "v3_field_two_reads", C.FIELD,
            "two field reads in a row, each from a fresh read of the same card",
            listOf(
                search(R.TAEUL, p()),
                detail("그 사람", "회사 이름이 어떻게 되나요?", R.TAEUL, R.TAEUL.company),
                detail("그분", "업종이 무엇인가요?", R.TAEUL, R.TAEUL.industry),
            ),
        )
        add(
            "v3_field_address_value", C.FIELD,
            "an address is shown from the card, and the word 이메일 does not make it a compose",
            listOf(
                search(R.MIROO, p()),
                V3Turn(
                    user = "그 사람 이메일 주소가 뭔가요?",
                    act = A.CONTACT_DETAIL,
                    outcome = O.CONTACT_DETAIL_SHOWN,
                    tools = listOf(GET),
                    forbidden = setOf(SEARCH, COMPOSE, CALENDAR, UPDATE, NOW),
                    args = mapOf(GET to mapOf("card_id" to R.MIROO.id)),
                    expectedTargetCardId = R.MIROO.id,
                    answerContains = listOf("${R.MIROO.name} 명함 정보입니다", R.MIROO.email),
                ),
            ),
        )
        add(
            "v3_field_missing_value_reported", C.FIELD,
            "a field the card does not have is reported as missing rather than filled in",
            listOf(
                search(R.SEUNGON_NO_EMAIL, p()),
                V3Turn(
                    user = "그 사람 이메일 주소가 뭔가요?",
                    act = A.CONTACT_DETAIL,
                    outcome = O.CONTACT_DETAIL_SHOWN,
                    tools = listOf(GET),
                    forbidden = setOf(SEARCH, COMPOSE, CALENDAR, UPDATE, NOW),
                    args = mapOf(GET to mapOf("card_id" to R.SEUNGON_NO_EMAIL.id)),
                    expectedTargetCardId = R.SEUNGON_NO_EMAIL.id,
                    answerContains = listOf("이 명함에는 정보가 없습니다"),
                ),
            ),
        )

        // ===== no-tool conversation ============================================================================
        add(
            "v3_no_tool_greeting", C.NO_TOOL,
            "conversational turns run nothing and claim nothing",
            listOf(smallTalk("안녕하십니까."), smallTalk("네 좋습니다.")),
        )
        add(
            "v3_no_tool_between_work", C.NO_TOOL,
            "an acknowledgement in the middle of real work leaves the focus exactly as it was",
            listOf(
                search(R.GANGYU, p()),
                smallTalk("네 좋습니다.", R.GANGYU.id),
                detail("그분", "회사 이름이 어떻게 되나요?", R.GANGYU, R.GANGYU.company),
            ),
        )

        // ===== reset ===========================================================================================
        add(
            "v3_reset_clears_target", C.RESET,
            "after a new session the anaphor has nothing to resolve to",
            listOf(
                search(R.HYORIN, p()),
                clarifyNoTarget("그 사람에게 ${MAIL_BODIES[2]}").copy(resetBefore = true),
            ),
            neverAnywhere = listOf(R.HYORIN.email),
        )
        add(
            "v3_reset_after_detail", C.RESET,
            "a reset after a detail read clears the same state a reset after a search does",
            listOf(
                search(R.NARAE, p()),
                detail("그 사람", "회사 이름이 어떻게 되나요?", R.NARAE, R.NARAE.company),
                V3Turn(
                    user = "그분 업종이 무엇인가요?",
                    act = A.CLARIFICATION_REQUIRED,
                    outcome = O.CLARIFICATION_REQUIRED,
                    forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW),
                    expectNoTarget = true,
                    answerContains = listOf("어떤 분을 말씀하시는지"),
                    resetBefore = true,
                ),
            ),
        )
        add(
            "v3_reset_then_second_session", C.RESET,
            "a full session, a reset, then a second full session that shares nothing with the first",
            listOf(
                search(R.JUNSEO, p()),
                composeMail("그 사람에게", R.JUNSEO, m()),
                clarifyNoTarget("그 사람에게 ${MAIL_BODIES[3]}").copy(resetBefore = true),
                search(R.SOLBIN, p()),
                composeMail("그분에게", R.SOLBIN, m()),
            ),
            neverAnywhere = emptyList(),
        )

        // ===== stale target prevention ===========================================================================
        add(
            "v3_stale_zero_result_clears_target", C.STALE,
            "a search that finds nobody clears the focus, so the next anaphor asks",
            listOf(
                search(R.TAEUL, p()),
                zeroResultSearch("갈원비"),
                clarifyNoTarget("그 사람에게 ${MAIL_BODIES[0]}"),
            ),
            neverAnywhere = listOf(R.TAEUL.email),
        )
        add(
            "v3_stale_ambiguous_clears_target", C.STALE,
            "an ambiguous search leaves candidates but no target, and the ordinal selects from them",
            listOf(
                search(R.MIROO, p()),
                ambiguousSearch(R.BOMI_LAW.name, R.BOMI_LAW, R.BOMI_DESIGN),
                clarifyAmbiguous("그 사람에게 ${MAIL_BODIES[1]}", R.BOMI_LAW, R.BOMI_DESIGN),
                ordinalPick("두 번째 분 명함 화면에 보여줘.", R.BOMI_DESIGN),
            ),
            neverAnywhere = listOf(R.MIROO.email),
        )
        add(
            "v3_stale_reset_between_write_and_read", C.STALE,
            "a reset between an edit and a question about it leaves nothing to read",
            listOf(
                search(R.GANGYU, p()),
                updateMemo("그 사람", R.GANGYU, "설비보류"),
                V3Turn(
                    user = "그분 메모에 뭐라고 적혀 있나요?",
                    act = A.CLARIFICATION_REQUIRED,
                    outcome = O.CLARIFICATION_REQUIRED,
                    forbidden = setOf(SEARCH, GET, COMPOSE, CALENDAR, UPDATE, NOW),
                    expectNoTarget = true,
                    answerContains = listOf("어떤 분을 말씀하시는지"),
                    resetBefore = true,
                ),
            ),
        )

        // ===== idempotency ========================================================================================
        add(
            "v3_idempotency_repeated_compose", C.IDEMPOTENCY,
            "the same compose asked twice opens one screen per turn, never two in one",
            listOf(
                search(R.HYORIN, p()),
                composeMail("그 사람에게", R.HYORIN, 0),
                composeMail("그 사람에게", R.HYORIN, 0),
            ),
        )
        add(
            "v3_idempotency_repeated_calendar", C.IDEMPOTENCY,
            "the same schedule asked twice writes one event per turn",
            listOf(
                search(R.NARAE, p()),
                attendeeCalendar("그 사람과 2027년 12월 2일 오후 4시 생산 협의 일정 만들어줘.", R.NARAE,
                    "2027-12-02T16:00", "고나래 일정"),
                attendeeCalendar("그 사람과 2027년 12월 2일 오후 4시 생산 협의 일정 만들어줘.", R.NARAE,
                    "2027-12-02T16:00", "고나래 일정"),
            ),
        )

        // ===== no false completion ==================================================================================
        add(
            "v3_truthful_compose_is_not_send", C.TRUTHFUL,
            "opening a compose screen is never reported as having sent anything",
            listOf(
                search(R.JUNSEO, p()),
                detail("그 사람", "회사 이름이 어떻게 되나요?", R.JUNSEO, R.JUNSEO.company),
                composeMail("그분에게", R.JUNSEO, m()).copy(
                    answerContains = listOf("메일 작성 화면을 열었습니다", "전송 전에 확인해 주세요"),
                    answerExcludes = listOf("전송했습니다", "발송했습니다", "전송 완료", "보냈습니다"),
                ),
            ),
        )
        add(
            "v3_truthful_body_mentions_schedule", C.TRUTHFUL,
            "an SMS whose body mentions 일정 is still an SMS; the screen opened, so the user is told it " +
                "opened rather than that something failed",
            listOf(
                search(R.SOLBIN, p()),
                V3Turn(
                    user = "그 사람에게 일정 한번 확인 부탁드린다고 문자 작성해줘.",
                    act = A.ACTION_COMPOSE,
                    outcome = O.COMPOSE_OPENED,
                    tools = listOf(GET, COMPOSE),
                    forbidden = setOf(SEARCH, CALENDAR, UPDATE, NOW),
                    args = mapOf(
                        GET to mapOf("card_id" to R.SOLBIN.id),
                        COMPOSE to mapOf("channel" to "sms", "to" to R.SOLBIN.mobile),
                    ),
                    argPatterns = mapOf(COMPOSE to mapOf("body" to "(?s).{2,}")),
                    sideEffects = 1,
                    composeTo = R.SOLBIN.mobile,
                    expectedTargetCardId = R.SOLBIN.id,
                    answerContains = listOf("문자 작성 화면을 열었습니다"),
                    answerExcludes = listOf("완료하지 못했습니다", "전송했습니다"),
                ),
            ),
            tags = listOf("collision"),
        )
        add(
            "v3_truthful_calendar_is_not_saved", C.TRUTHFUL,
            "opening the calendar screen is never reported as having saved the event",
            listOf(
                search(R.YERAM, p()),
                bareCalendar("2028년 5월 6일 오후 3시 정기 점검 일정 만들어줘.", "2028-05-06T15:00", "일정",
                    R.YERAM.id),
            ),
        )

        // ===== recipient provenance ====================================================================================
        add(
            "v3_provenance_only_verified_address", C.PROVENANCE,
            "the recipient is the address read this turn and nobody else's",
            listOf(search(R.TAEUL, p()), composeMail("그 사람에게", R.TAEUL, m())),
            neverAnywhere = R.PEOPLE.filter { it.id != R.TAEUL.id }.map { it.email },
        )
        add(
            "v3_provenance_card_text_is_data", C.PROVENANCE,
            "an instruction written into a card field is data and must not become an action",
            listOf(
                search(R.INJECTED, p()),
                composeMail("그 사람에게", R.INJECTED, m()).copy(
                    answerContains = listOf("메일 작성 화면을 열었습니다"),
                    answerExcludes = listOf("지워", "삭제"),
                ),
            ),
            tags = listOf("injection"),
        )
        add(
            "v3_provenance_switch_does_not_leak", C.PROVENANCE,
            "after a target switch the previous address must not appear in any argument or answer",
            listOf(
                search(R.MIROO, p()),
                search(R.GANGYU, p()),
                composeMail("그 사람에게", R.GANGYU, m()).copy(forbiddenValues = listOf(R.MIROO.email)),
            ),
            neverAnywhere = listOf(R.MIROO.email),
        )

        // ===== safe failure =============================================================================================
        add(
            "v3_failure_backend_down", C.SAFE_FAILURE,
            "when the store is unreachable the turn fails honestly and leaves no target behind",
            listOf(
                V3Turn(
                    user = "${R.NARAE.name} 명함 어디 있지 찾아줘.",
                    act = A.CONTACT_SEARCH,
                    outcome = O.FAILED,
                    tools = listOf(SEARCH),
                    forbidden = setOf(COMPOSE, CALENDAR, UPDATE, GET, NOW),
                    argPatterns = mapOf(SEARCH to mapOf("query" to "(?s).*${Regex.escape(R.NARAE.name)}.*")),
                    expectNoTarget = true,
                    answerContains = listOf("못했습니다"),
                    answerExcludes = listOf("검색 결과입니다"),
                ),
                clarifyNoTarget("그 사람에게 ${MAIL_BODIES[0]}"),
            ),
            searchFailure = true,
        )
        add(
            "v3_failure_no_phone_for_sms", C.SAFE_FAILURE,
            "a card with no number cannot receive an SMS, and the gap is named rather than filled",
            listOf(
                search(R.DAHUI_NO_PHONE, p()),
                V3Turn(
                    user = "그 사람에게 ${SMS_BODIES[0]}",
                    act = A.ACTION_COMPOSE,
                    outcome = O.CONTACT_DETAIL_SHOWN,
                    tools = listOf(GET),
                    forbidden = setOf(COMPOSE, CALENDAR, UPDATE, SEARCH, NOW),
                    args = mapOf(GET to mapOf("card_id" to R.DAHUI_NO_PHONE.id)),
                    expectedTargetCardId = R.DAHUI_NO_PHONE.id,
                    answerContains = listOf("전화번호 정보가 없습니다"),
                    answerExcludes = listOf("열었습니다"),
                ),
            ),
        )
        add(
            "v3_failure_no_email_for_mail", C.SAFE_FAILURE,
            "the same rule on the mail channel: no address means no screen and no invented recipient",
            listOf(
                search(R.SEUNGON_NO_EMAIL, p()),
                V3Turn(
                    user = "그 사람에게 ${MAIL_BODIES[0]}",
                    act = A.ACTION_COMPOSE,
                    outcome = O.CONTACT_DETAIL_SHOWN,
                    tools = listOf(GET),
                    forbidden = setOf(COMPOSE, CALENDAR, UPDATE, SEARCH, NOW),
                    args = mapOf(GET to mapOf("card_id" to R.SEUNGON_NO_EMAIL.id)),
                    expectedTargetCardId = R.SEUNGON_NO_EMAIL.id,
                    answerContains = listOf("이메일 주소 정보가 없습니다"),
                    answerExcludes = listOf("열었습니다"),
                ),
            ),
        )

        // ===== long-range memory ==========================================================================================
        add(
            "v3_long_range_eight_turns", C.LONG_RANGE,
            "eight turns in which the reference distance grows through a clock lookup, a schedule, a " +
                "concept question and a refusal before the anaphor is used again",
            listOf(
                search(R.NARAE, p()),
                detail("그 사람", "회사 이름이 어떻게 되나요?", R.NARAE, R.NARAE.company),
                clock("지금 몇 시인지 확인 좀."),
                bareCalendar("2027년 2월 5일 오후 1시 생산 점검 일정 만들어줘.", "2027-02-05T13:00", "일정",
                    R.NARAE.id, listOf(R.NARAE.email)),
                information("생산 용어 뜻이 궁금해.", "뜻"),
                vetoed("그분한테 전화 좀 걸어줘.", "전화 걸기는 지원하지 않습니다"),
                composeMail("그분에게", R.NARAE, m()),
                detail("그 사람", "업종이 무엇인가요?", R.NARAE, R.NARAE.industry),
            ),
            tags = listOf("long_range"),
        )
        add(
            "v3_long_range_two_people_nine_turns", C.LONG_RANGE,
            "nine turns alternating bound and unbound work across two people, so an inherited target " +
                "shows up as a wrong attendee rather than as nothing at all",
            listOf(
                search(R.JUNSEO, p()),
                composeMail("그 사람에게", R.JUNSEO, m()),
                bareCalendar("2027년 3월 18일 오전 10시 기획 점검 일정 만들어줘.", "2027-03-18T10:00", "일정",
                    R.JUNSEO.id, listOf(R.JUNSEO.email)),
                search(R.SOLBIN, p()),
                bareCalendar("2027년 4월 21일 오후 2시 공정 점검 일정 만들어줘.", "2027-04-21T14:00", "일정",
                    R.SOLBIN.id, listOf(R.SOLBIN.email)),
                composeMail("그분에게", R.SOLBIN, m()),
                detail("그 사람", "업종이 무엇인가요?", R.SOLBIN, R.SOLBIN.industry),
                recallDenied("내가 좀 전에 명함 지워달라고 했나?"),
                bareCalendar("2027년 5월 25일 오후 5시 마감 점검 일정 만들어줘.", "2027-05-25T17:00", "일정",
                    R.SOLBIN.id, listOf(R.SOLBIN.email)),
            ),
            tags = listOf("long_range"),
        )
        add(
            "v3_long_range_slots_and_recovery", C.LONG_RANGE,
            "eight turns in which three different missing slots are each followed by the turn that " +
                "supplies them, so a clarification is shown to be resumable",
            listOf(
                search(R.YERAM, p()),
                missingCalendarTime("일정 하나 만들어줘.", R.YERAM.id),
                bareCalendar("2027년 6월 30일 오전 9시 편집 점검 일정 만들어줘.", "2027-06-30T09:00", "일정",
                    R.YERAM.id),
                missingRecipient("메일 하나 작성해줘.", "메일 수신자", R.YERAM.id, R.YERAM.email),
                composeMail("그 사람에게", R.YERAM, m()),
                missingUpdateField("그분", R.YERAM.id),
                updateMemo("그분", R.YERAM, "장기거래"),
                detail("그 사람", "메모에 뭐라고 적혀 있나요?", R.YERAM, "장기거래"),
            ),
            tags = listOf("long_range"),
        )
        add(
            "v3_long_range_collision_session", C.LONG_RANGE,
            "eight turns whose target is a person whose name spells an action word throughout, so a " +
                "collision would surface at every step rather than only at the lookup",
            listOf(
                search(R.HOERIN_LOOKUP, 0),
                detail("그 사람", "회사 이름이 어떻게 되나요?", R.HOERIN_LOOKUP, R.HOERIN_LOOKUP.company),
                composeMail("그분에게", R.HOERIN_LOOKUP, m()),
                bareCalendar("2027년 7월 14일 오후 4시 분석 점검 일정 만들어줘.", "2027-07-14T16:00", "일정",
                    R.HOERIN_LOOKUP.id, listOf(R.HOERIN_LOOKUP.email)),
                updateMemo("그 사람", R.HOERIN_LOOKUP, "분석완료"),
                detail("그분", "메모에 뭐라고 적혀 있나요?", R.HOERIN_LOOKUP, "분석완료"),
                clock("오늘 날짜 좀 확인 부탁해."),
                composeSms("그 사람에게", R.HOERIN_LOOKUP, 2),
            ),
            tags = listOf("long_range", "collision"),
        )

        add(
            "v3_long_range_chain_eight_turns", C.CHAIN,
            "eight turns crossing every production tool on one person, so a chain that only got its " +
                "first call right cannot survive to the end",
            listOf(
                search(R.MIROO, p()),
                detail("그 사람", "회사 이름이 어떻게 되나요?", R.MIROO, R.MIROO.company),
                composeMail("그분에게", R.MIROO, m()),
                updateMemo("그 사람", R.MIROO, "품질확정"),
                detail("그분", "메모에 뭐라고 적혀 있나요?", R.MIROO, "품질확정"),
                clock("지금 몇 시인지 확인 좀."),
                attendeeCalendar("그 사람과 2027년 8월 26일 오전 10시 품질 협의 일정 만들어줘.", R.MIROO,
                    "2027-08-26T10:00", "옥미루 일정"),
                composeSms("그분에게", R.MIROO, 1),
            ),
            tags = listOf("long_range"),
        )
        add(
            "v3_long_range_focus_eight_turns", C.FOCUS_TARGET,
            "eight turns alternating attendee-less schedules with bound work, so an inherited target " +
                "shows up as a concrete wrong attendee",
            listOf(
                search(R.GANGYU, p()),
                bareCalendar("2027년 9월 15일 오전 8시 설비 점검 일정 만들어줘.", "2027-09-15T08:00", "일정",
                    R.GANGYU.id, listOf(R.GANGYU.email)),
                composeMail("그 사람에게", R.GANGYU, m()),
                bareCalendar("2027년 10월 20일 오후 3시 예비 점검 일정 만들어줘.", "2027-10-20T15:00", "일정",
                    R.GANGYU.id, listOf(R.GANGYU.email)),
                detail("그분", "업종이 무엇인가요?", R.GANGYU, R.GANGYU.industry),
                smallTalk("네 좋습니다.", R.GANGYU.id),
                bareCalendar("2027년 11월 25일 오후 6시 연말 점검 일정 만들어줘.", "2027-11-25T18:00", "일정",
                    R.GANGYU.id, listOf(R.GANGYU.email)),
                composeSms("그 사람에게", R.GANGYU, 0),
            ),
            tags = listOf("long_range"),
        )
        add(
            "v3_long_range_slot_symmetry_eight_turns", C.SLOT,
            "eight turns pinning both channels' clarification symmetry twice each, with real work in " +
                "between so the state is never trivially empty",
            listOf(
                search(R.HYORIN, p()),
                missingRecipient("메일 하나 작성해줘.", "메일 수신자", R.HYORIN.id, R.HYORIN.email),
                missingRecipient("문자 하나 작성해줘.", "문자 수신자", R.HYORIN.id, R.HYORIN.mobile),
                composeMail("그 사람에게", R.HYORIN, m()),
                detail("그분", "회사 이름이 어떻게 되나요?", R.HYORIN, R.HYORIN.company),
                missingRecipient("메일 써줘.", "메일 수신자", R.HYORIN.id, R.HYORIN.email),
                missingRecipient("문자 보내줘.", "문자 수신자", R.HYORIN.id, R.HYORIN.mobile),
                composeSms("그분에게", R.HYORIN, 2),
            ),
            tags = listOf("long_range"),
        )
        add(
            "v3_long_range_provenance_eight_turns", C.PROVENANCE,
            "eight turns across three people, each action asserting that only the address read in that " +
                "same turn is used and the other two never appear",
            listOf(
                search(R.SOLBIN, p()),
                composeMail("그 사람에게", R.SOLBIN, m()).copy(
                    forbiddenValues = listOf(R.YERAM.email, R.TAEUL.email),
                ),
                search(R.YERAM, p()),
                composeMail("그분에게", R.YERAM, m()).copy(
                    forbiddenValues = listOf(R.SOLBIN.email, R.TAEUL.email),
                ),
                search(R.TAEUL, p()),
                composeMail("그 사람에게", R.TAEUL, m()).copy(
                    forbiddenValues = listOf(R.SOLBIN.email, R.YERAM.email),
                ),
                detail("그분", "회사 이름이 어떻게 되나요?", R.TAEUL, R.TAEUL.company),
                attendeeCalendar("그 사람과 2028년 1월 12일 오후 2시 배송 협의 일정 만들어줘.", R.TAEUL,
                    "2028-01-12T14:00", "설태을 일정"),
            ),
            tags = listOf("long_range"),
        )

        return out
    }
}
