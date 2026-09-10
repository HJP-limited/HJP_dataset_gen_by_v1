package com.example.hjp.v4char

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord

/**
 * Shared cards and helpers for the v4 characterization suite.
 *
 * The cards are declared here, not read from an evaluation dataset, and no test in this package
 * names a scenario index, a case id or a fixture id. That is deliberate: a characterization test that
 * consulted the evaluation set would be measuring the same sentences the evaluator scores, and a
 * production fix tuned against it would generalise to nothing.
 *
 * Names are chosen for the *shape* they have, and each shape is documented where it is declared:
 * a name that ends in a syllable a particle-stripper would eat, a two-syllable surname, a Latin
 * name, a spaced name, a name that is also a common noun. A future card with the same shape must be
 * handled by the same rule.
 */
object V4Cards {

    /** An ordinary target. Everything that is *not* about name shape uses this one. */
    val ANCHOR = BusinessCardRecord(
        id = "V401", name = "차솔빈", company = "이레바이오", title = "공정개발", department = "생산기술",
        industry = "바이오", location = "청주", email = "solbin@irebio.example.net",
        phone = "043-555-0101", mobile = "010-4400-0001", memo = "1차 미팅 완료",
    )

    /** The second person, present in the store: the "new target resolves" control. */
    val SECOND = BusinessCardRecord(
        id = "V402", name = "봉예람", company = "달빛출판", title = "편집장", department = "단행본",
        industry = "출판", location = "파주", email = "yeram@dalbit.example.net",
        phone = "031-555-0102", mobile = "010-4400-0002",
    )

    /** The third person, for correction chains B -> C. */
    val THIRD = BusinessCardRecord(
        id = "V403", name = "구민서", company = "온새미로", title = "리서처", department = "전략",
        industry = "컨설팅", location = "서울", email = "minseo@onsemiro.example.net",
        phone = "02-555-0103", mobile = "010-4400-0003",
    )

    // ---- name-shape cards ------------------------------------------------------------------------

    /** Ends in 을 — the syllable a naive particle stripper removes, leaving a different person. */
    val ENDS_LIKE_PARTICLE = BusinessCardRecord(
        id = "V410", name = "하도을", company = "남강기전", title = "설비반장",
        industry = "제조", email = "doeul@namgang.example.net", mobile = "010-4400-0010",
    )

    /** The person a naive stripper would land on instead: 하도을 minus 을. */
    val STRIPPED_TWIN = BusinessCardRecord(
        id = "V411", name = "하도", company = "세정테크", title = "품질관리",
        industry = "제조", email = "hado@sejeong.example.net", mobile = "010-4400-0011",
    )

    /** Ends in 좀 — the same trap for the "좀" politeness particle. */
    val ENDS_LIKE_JOM = BusinessCardRecord(
        id = "V412", name = "예서좀", company = "가온물산", title = "구매담당",
        industry = "유통", email = "jom@gaon.example.net", mobile = "010-4400-0012",
    )

    /** Two-syllable Korean surname (복성). */
    val COMPOUND_SURNAME = BusinessCardRecord(
        id = "V413", name = "남궁세연", company = "예림엔지니어링", title = "구조설계",
        industry = "건설", email = "seyeon@yerim.example.net", mobile = "010-4400-0013",
    )

    /** A rare single-syllable surname. */
    val RARE_SURNAME = BusinessCardRecord(
        id = "V414", name = "빙현우", company = "한올식품", title = "품질팀장",
        industry = "식품", email = "hyunwoo@hanol.example.net", mobile = "010-4400-0014",
    )

    /** A Latin-script name. */
    val LATIN_NAME = BusinessCardRecord(
        id = "V415", name = "Marcus Lindqvist", nameEn = "Marcus Lindqvist",
        company = "Nordvik AB", title = "Sourcing Lead",
        industry = "무역", email = "marcus@nordvik.example.net", mobile = "010-4400-0015",
    )

    /** A name written with a space inside it. */
    val SPACED_NAME = BusinessCardRecord(
        id = "V416", name = "선우 재하", company = "미르소재", title = "연구소장",
        industry = "소재", email = "jaeha@mir.example.net", mobile = "010-4400-0016",
    )

    /** A long name. */
    val LONG_NAME = BusinessCardRecord(
        id = "V417", name = "황보람슬기찬", company = "다솜네트웍스", title = "네트워크엔지니어",
        industry = "IT", email = "seulgi@dasom.example.net", mobile = "010-4400-0017",
    )

    /** A name that is also an ordinary noun in this domain's neighbourhood. */
    val COMMON_NOUN_NAME = BusinessCardRecord(
        id = "V418", name = "정보라", company = "누리컴즈", title = "기획", department = "전략기획",
        industry = "IT", email = "bora@nuri.example.net", mobile = "010-4400-0018",
    )

    /** Same name as [COMMON_NOUN_NAME] is a homograph of; distinct person, distinct card. */
    val COMPANY_LIKE_NAME = BusinessCardRecord(
        id = "V419", name = "한빛나", company = "한빛물산", title = "총무",
        industry = "유통", email = "bitna@hanbit.example.net", mobile = "010-4400-0019",
    )

    /** Namesakes, for the ambiguity axis. */
    val TWIN_A = BusinessCardRecord(
        id = "V420", name = "탁보미", company = "정명법무", title = "법무사",
        industry = "법률", email = "bomi.law@jm.example.net", mobile = "010-4400-0020",
    )
    val TWIN_B = BusinessCardRecord(
        id = "V421", name = "탁보미", company = "결디자인", title = "UX디자이너",
        industry = "디자인", email = "bomi.ux@gy.example.net", mobile = "010-4400-0021",
    )

    /** A contact with no email at all, for the missing-slot axis. */
    val NO_EMAIL = BusinessCardRecord(
        id = "V430", name = "석지운", company = "무영건축", title = "감리",
        industry = "건설", mobile = "010-4400-0030",
    )

    val NAME_SHAPES = listOf(
        ENDS_LIKE_PARTICLE, STRIPPED_TWIN, ENDS_LIKE_JOM, COMPOUND_SURNAME, RARE_SURNAME,
        LATIN_NAME, SPACED_NAME, LONG_NAME, COMMON_NOUN_NAME, COMPANY_LIKE_NAME,
    )
}

/** Request wordings, so a contract is stated once and checked against many phrasings. */
object V4Phrasing {

    /** Compose requests. Plain, polite, contracted, and with the target trailing the predicate. */
    val MAIL_REQUESTS = listOf(
        "제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해줘.",
        "제목은 발주 문의, 내용은 수량 확인 부탁드립니다 라고 메일 작성해 주시겠어요?",
        "메일 좀 써 줄래? 제목은 발주 문의, 내용은 수량 확인 부탁드립니다.",
    )

    val SMS_REQUESTS = listOf(
        "도착했다고 문자 작성해줘.",
        "도착했다고 문자 작성해 주세요.",
        "문자 좀 보내 줄래? 도착했다고.",
    )

    val CALENDAR_REQUESTS = listOf(
        "2027년 5월 6일 오후 4시 협의 일정 만들어줘.",
        "2027년 5월 6일 오후 4시에 협의 일정 좀 잡아 주시겠어요?",
    )

    val UPDATE_REQUESTS = listOf(
        "메모를 재검토로 수정해줘.",
        "메모 좀 재검토로 바꿔 주세요.",
    )

    /** Anaphors that point at the contact in focus. */
    val ANAPHORS = listOf("그 사람", "그분", "이 사람", "그 연락처")
}

/** Assertions the whole suite shares, so one contract has one wording. */
object V4Assert {

    /** Every external surface, checked for a value that must never have been used. */
    fun assertNeverContacted(h: MultiturnScenarioHarness, vararg values: String) {
        values.filter { it.isNotBlank() }.forEach { value ->
            require(h.messages.drafts.none { it.to == value }) {
                "$value was used as a compose recipient"
            }
            require(h.calendar.drafts.none { value in it.attendeeEmails }) {
                "$value was used as a calendar attendee"
            }
        }
    }

    /** True when the turn ended by asking the user something rather than by acting. */
    fun asksSomething(outcome: TurnOutcomeType?): Boolean =
        outcome == TurnOutcomeType.CLARIFICATION_REQUIRED

    val ACTION_TOOLS = setOf("open_compose", "create_calendar_event", "update_business_card")
}
