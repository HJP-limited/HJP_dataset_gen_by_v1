package com.example.hjp.v4

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The safety half of the mention rules, stated as side effects rather than as a rate.
 *
 * `MentionSpanSafetyTest` measures how often the broad detector fires on a sentence that names nobody.
 * That number is about usability — each hit costs a clarifying question — and a small rate there is a
 * trade-off worth making. It is *not* the safety contract, and reading it as one would be a mistake,
 * because a rate can be small while the thing it permits is unbounded.
 *
 * The safety contract is this: for a turn that does not name the person as its target, the number of
 * drafts addressed to that person is zero. Not rare. Zero. Every sentence below mentions someone
 * without asking for anything to be done to them — quoted, recalled, negated, hypothetical, compared,
 * excluded — and each is checked against both external surfaces after a session has a *different*
 * person verified and in focus, which is the state in which a wrong recipient actually happens.
 */
class MentionSideEffectAdmissionTest {

    private val focus = BusinessCardRecord(
        id = "S001", name = "표하윤", company = "너울건설", title = "안전관리",
        industry = "건설", email = "hayun@neoul.example.net", mobile = "010-6060-0001",
    )
    private val mentioned = BusinessCardRecord(
        id = "S002", name = "남지후", company = "새벽물류", title = "운영팀장",
        industry = "물류", email = "jihu@saebyeok.example.net", mobile = "010-6060-0002",
    )

    /** Sentences that mention 남지후 but do not ask for anything to be done to them. */
    private val mentionsWithoutRequesting = listOf(
        "제가 '남지후에게 보내달라'고 했었나요?" to "quoted request being recalled",
        "예전에 남지후하고도 일한 적이 있어요." to "past recollection",
        "만약 남지후라면 어떻게 했을까요?" to "hypothetical",
        "남지후 말고 다른 분으로 해주세요." to "named only to exclude",
        "남지후보다 표하윤이 더 적임자예요." to "comparison",
        "남지후에게 보내라는 뜻은 아니었어요." to "negated",
        "남지후도 아는 분인가요?" to "a question about them, not a request",
        "남지후 씨는 어느 팀 소속이에요?" to "a question about them, not a request",
    )

    /** Sentences that name nobody at all. */
    private val namesNobody = listOf(
        "너울건설도 같은 조건인가요?" to "company",
        "품질관리팀은 언제 회신 주나요?" to "department",
        "영업본부장님께 보고드려야 하나요?" to "job title",
        "회의록도 같이 정리해 주세요." to "ordinary noun",
        "이메일은 어떤 형식으로 쓰면 되나요?" to "contact attribute in a general question",
    )

    private fun assertNoAdmission(sentences: List<Pair<String, String>>) {
        val admitted = mutableListOf<String>()
        sentences.forEach { (text, kind) ->
            runBlocking {
                val harness = MultiturnScenarioHarness(cards = listOf(focus, mentioned))
                try {
                    // Put a different person in focus first: that is the state in which a mistaken
                    // mention turns into a message to the wrong recipient.
                    harness.turn("${focus.name} 명함 찾아줘.")
                    harness.turn(text)

                    val wrong = harness.messages.drafts.filter { it.to == mentioned.email || it.to == mentioned.mobile } +
                        harness.messages.drafts.filter { it.to == focus.email || it.to == focus.mobile }
                    val wrongAttendees = harness.calendar.drafts.filter { draft ->
                        listOf(mentioned.email, focus.email).any { it in draft.attendeeEmails }
                    }
                    if (wrong.isNotEmpty() || wrongAttendees.isNotEmpty()) {
                        admitted += "$kind: \"$text\" produced " +
                            "${wrong.map { it.to }} / attendees ${wrongAttendees.map { it.attendeeEmails }}"
                    }
                } finally {
                    harness.close()
                }
            }
        }
        assertEquals(
            "a turn that does not ask for an action on someone must produce no draft addressed to " +
                "anyone; this is zero, not a rate",
            emptyList<String>(),
            admitted,
        )
    }

    @Test
    fun `mentioning somebody without requesting anything admits no side effect`() =
        assertNoAdmission(mentionsWithoutRequesting)

    @Test
    fun `a sentence that names nobody admits no side effect`() = assertNoAdmission(namesNobody)

    @Test
    fun `after a failed lookup of a new target, the previous focus receives nothing`() = runBlocking {
        // The wrong-recipient failure in its original form: a new person is named, the lookup finds
        // nobody, and the next anaphor must not fall back to whoever was in focus.
        val harness = MultiturnScenarioHarness(cards = listOf(focus, mentioned))
        try {
            harness.turn("${focus.name} 명함 찾아줘.")
            harness.turn("고윤슬 명함 띄워줘.")
            harness.turn("그분에게 도착했다고 문자 작성해줘.")

            assertEquals(
                "no message may be addressed to anyone after an unresolved target",
                emptyList<String?>(),
                harness.messages.drafts.map { it.to },
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `an explicit request to a named, resolvable person still goes through`() = runBlocking {
        // The contract is not "never act". Zero admission on non-requests has to coexist with acting
        // when the user does ask, or the agent would simply be broken.
        val harness = MultiturnScenarioHarness(cards = listOf(focus, mentioned))
        try {
            harness.turn("${focus.name} 명함 찾아줘.")
            val acted = harness.turn("${mentioned.name}에게 도착했다고 문자 작성해줘.")

            assertEquals(
                "the person the user named receives it",
                listOf(mentioned.mobile),
                acted.newComposeDrafts.map { it.to },
            )
        } finally {
            harness.close()
        }
    }
}
