package com.hjp.agent.routing

/**
 * Output-safety gate, not a tool router. It never chooses or creates a tool call; it only prevents
 * ungrounded free text from being presented as contact data when no contact tool ran.
 */
object ContactGroundingGuard {
    private val contactEvidenceTerms = listOf(
        "명함", "연락처", "전화번호", "휴대폰", "이메일", "메일 주소",
        "회사 정보", "소속", "직책", "대표의", "담당자", "담당하는",
        "사람을 찾아", "사람을 찾", "연락해",
    )
    private val anyToolTerms = contactEvidenceTerms + listOf(
        "검색해", "검색해 줘", "일정", "캘린더", "메일을", "메일 초안", "이메일 초안",
        "문자", "SMS", "몇 시", "오늘 날짜", "현재 시간", "날짜와 시각",
        "수정해", "바꿔", "변경해", "비워 줘",
    )

    fun requiresToolEvidence(userText: String): Boolean =
        contactEvidenceTerms.any { userText.contains(it, ignoreCase = true) }

    fun requiresAnyToolEvidence(userText: String): Boolean =
        anyToolTerms.any { userText.contains(it, ignoreCase = true) }

    const val UNGROUNDED_SAFE_RESPONSE =
        "연락처 도구가 호출되지 않아 확인된 명함 정보를 제공할 수 없습니다."

    const val UNEXECUTED_TOOL_SAFE_RESPONSE =
        "요청에 필요한 도구가 호출되지 않아 작업을 수행하지 않았습니다."
}
