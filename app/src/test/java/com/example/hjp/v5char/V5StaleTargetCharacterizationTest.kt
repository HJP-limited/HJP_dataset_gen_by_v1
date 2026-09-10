package com.example.hjp.v5char

import com.example.hjp.v5char.V5CharacterizationFixture.ALICE
import com.example.hjp.v5char.V5CharacterizationFixture.ALICE_EMAIL
import com.example.hjp.v5char.V5CharacterizationFixture.ALICE_PHONE
import com.example.hjp.v5char.V5CharacterizationFixture.BRIAN
import com.example.hjp.v5char.V5CharacterizationFixture.BRIAN_EMAIL
import com.example.hjp.v5char.V5CharacterizationFixture.CHOI
import com.example.hjp.v5char.V5CharacterizationFixture.CHOI_EMAIL
import com.example.hjp.v5char.V5CharacterizationFixture.calendar
import com.example.hjp.v5char.V5CharacterizationFixture.compose
import com.example.hjp.v5char.V5CharacterizationFixture.datetime
import com.example.hjp.v5char.V5CharacterizationFixture.dependencyFindings
import com.example.hjp.v5char.V5CharacterizationFixture.get
import com.example.hjp.v5char.V5CharacterizationFixture.search
import com.example.hjp.v5char.V5CharacterizationFixture.turn
import com.example.hjp.v5char.V5CharacterizationFixture.update
import com.example.hjp.v5char.V5CharacterizationFixture.verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An action built from a contact nobody verified, on a turn the dataset never labelled.
 *
 * ## The defect this characterizes
 *
 * The frozen host device scorer asks `expected_route in ("search", "followup")` before it will look
 * for a missing read. That is a property of the *dataset*, not of the turn: 58 of the 386 frozen
 * turns carry no expected route at all, and an action on one of those could invite anybody with
 * nothing in the scorer to notice.
 *
 * The Kotlin side had the mirror-image hole. Its condition required an empty read list, so a turn
 * that ran `search_contacts` and then composed to an address the search never returned — a ranking
 * cannot return an address — satisfied "a read happened" and was passed.
 *
 * Every case below is a real wrong-recipient shape. Each must produce a finding, and it must do so
 * without the dataset's help.
 */
class V5StaleTargetCharacterizationTest {

    private fun assertCaught(what: String, findings: List<String>) {
        assertTrue("$what produced no finding", findings.isNotEmpty())
    }

    @Test
    fun `an unlabelled turn that invites an unread attendee is caught`() {
        val observed = turn(
            calls = listOf(datetime(), calendar(attendees = listOf(ALICE_EMAIL))),
            question = "내일 3시에 일정 잡아줘",
            expectedRoute = null,
            depth = 2,
            selectedCardId = ALICE,
            previousFocusCardId = ALICE,
        )
        assertCaught("an unread attendee", dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `an unlabelled turn that composes to an unread address is caught`() {
        val observed = turn(
            calls = listOf(compose(to = ALICE_EMAIL)),
            question = "메일 초안 하나 만들어줘",
            expectedRoute = null,
            depth = 2,
            selectedCardId = ALICE,
            previousFocusCardId = ALICE,
        )
        assertCaught("an unread recipient", dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `a phone recipient is judged the same way an address is`() {
        val observed = turn(
            calls = listOf(compose(to = ALICE_PHONE, channel = "sms", subject = null)),
            question = "문자 하나 보내게 준비해줘",
            expectedRoute = null,
            depth = 2,
            previousFocusCardId = ALICE,
        )
        assertCaught("an unread phone recipient", dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `a card edit whose target was never read is caught`() {
        val observed = turn(
            calls = listOf(update(cardId = ALICE)),
            question = "메모 좀 바꿔줘",
            expectedRoute = null,
            depth = 2,
            selectedCardId = ALICE,
            previousFocusCardId = ALICE,
            answer = "명함을 수정했습니다.",
        )
        assertCaught("an unverified edit target", dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `a ranking is not a verification`() {
        val observed = turn(
            calls = listOf(search("검색"), compose(to = ALICE_EMAIL)),
            question = "메일 준비해줘",
            expectedRoute = "search",
            depth = 1,
            selectedCardId = ALICE,
        )
        val findings = dependencyFindings(observed)
        assertCaught("search_contacts alone", findings.map { it.detail })
        assertTrue(
            "the finding should say no detail read happened",
            findings.any { it.provenance.any { line -> line.contains("NO_GET_CONTACT_IN_TURN") } },
        )
    }

    @Test
    fun `reading one card does not verify an action aimed at another`() {
        val observed = turn(
            calls = listOf(search("검색"), get(BRIAN), compose(to = ALICE_EMAIL)),
            question = "메일 준비해줘",
            expectedRoute = "followup",
            depth = 2,
            selectedCardId = BRIAN,
        )
        val findings = dependencyFindings(observed)
        assertCaught("a read of a different card", findings.map { it.detail })
        assertTrue(
            "the finding should name the wrong-card case",
            findings.any {
                it.provenance.any { line -> line.contains("GET_CONTACT_FOR_DIFFERENT_CARD") }
            },
        )
    }

    @Test
    fun `a read from the previous turn is not a read from this one`() {
        val observed = turn(
            calls = listOf(compose(to = BRIAN_EMAIL)),
            question = "그럼 메일 준비해줘",
            expectedRoute = null,
            depth = 3,
            selectedCardId = BRIAN,
            previousFocusCardId = BRIAN,
        )
        val findings = dependencyFindings(observed, listOf(verification(BRIAN, "1/2")))
        assertCaught("a read from an earlier turn", findings.map { it.detail })
        assertTrue(
            "the finding should name the earlier-turn case",
            findings.any {
                it.provenance.any { line -> line.contains("GET_CONTACT_IN_EARLIER_TURN") }
            },
        )
    }

    @Test
    fun `a read from before a correction does not verify what came after it`() {
        val observed = turn(
            calls = listOf(
                search("첫 검색"), get(ALICE),
                search("아니 다른 사람"), compose(to = ALICE_EMAIL),
            ),
            question = "아니 그 사람 말고 다시 찾아서 메일 준비해줘",
            expectedRoute = "followup",
            depth = 2,
            selectedCardId = ALICE,
        )
        val findings = dependencyFindings(observed)
        assertCaught("a pre-correction read", findings.map { it.detail })
        assertTrue(
            "the finding should name the earlier target epoch",
            findings.any {
                it.provenance.any { line -> line.contains("GET_CONTACT_IN_EARLIER_TARGET_EPOCH") }
            },
        )
    }

    @Test
    fun `a read from an earlier session generation is not a read from this one`() {
        val observed = turn(
            calls = listOf(compose(to = CHOI_EMAIL)),
            question = "메일 준비해줘",
            depth = 1,
            sessionGeneration = 1L,
        )
        val findings = dependencyFindings(
            observed, listOf(verification(CHOI, "1/4", sessionGeneration = 0L)),
        )
        assertCaught("a read from an older generation", findings.map { it.detail })
        assertTrue(
            "the finding should name the generation case",
            findings.any {
                it.provenance.any { line ->
                    line.contains("GET_CONTACT_IN_EARLIER_SESSION_GENERATION")
                }
            },
        )
    }

    @Test
    fun `a read that happens after the action does not verify it`() {
        val observed = turn(
            calls = listOf(compose(to = ALICE_EMAIL), get(ALICE)),
            question = "메일 준비해줘",
            depth = 2,
            selectedCardId = ALICE,
        )
        val findings = dependencyFindings(observed)
        assertCaught("a read after the action", findings.map { it.detail })
        assertTrue(
            "the finding should say the read came too late",
            findings.any {
                it.provenance.any { line -> line.contains("GET_CONTACT_AFTER_ACTION") }
            },
        )
    }

    @Test
    fun `one unverified attendee among verified ones is still caught`() {
        val observed = turn(
            calls = listOf(
                search("검색"), get(ALICE), get(BRIAN),
                calendar(attendees = listOf(ALICE_EMAIL, CHOI_EMAIL, BRIAN_EMAIL)),
            ),
            question = "세 사람 일정 잡아줘",
            expectedRoute = null,
            depth = 2,
        )
        val findings = dependencyFindings(observed)
        assertEquals("exactly the unverified attendee", 1, findings.size)
        assertTrue(
            "the finding should name the attendee path",
            findings.single().provenance.any { it.contains("attendee_emails[1]") },
        )
    }

    @Test
    fun `the previous focus reaching an argument is caught even with a read of somebody else`() {
        val observed = turn(
            calls = listOf(get(BRIAN), calendar(attendees = listOf(ALICE_EMAIL))),
            question = "일정 잡아줘",
            expectedRoute = null,
            depth = 3,
            selectedCardId = BRIAN,
            previousFocusCardId = ALICE,
        )
        val findings = dependencyFindings(observed)
        assertCaught("a previous-focus attendee", findings.map { it.detail })
        assertTrue(
            "the finding should mark the value stale",
            findings.any { it.provenance.any { line -> line.contains("stale=true") } },
        )
    }

    @Test
    fun `a contact value nested where the schema did not plan for it is still caught`() {
        val observed = turn(
            calls = listOf(
                "create_calendar_event" to V5CharacterizationFixture.args(
                    """{"title":"일정","start_time":"2026-08-27T15:00",""" +
                        """"invitees":{"primary":["$ALICE_EMAIL"]}}""",
                ),
            ),
            question = "일정 잡아줘",
            expectedRoute = null,
            depth = 2,
        )
        assertCaught("a nested contact value", dependencyFindings(observed).map { it.detail })
    }
}
