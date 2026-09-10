package com.example.hjp.v5char

import com.example.hjp.v5char.V5CharacterizationFixture.ALICE
import com.example.hjp.v5char.V5CharacterizationFixture.ALICE_EMAIL
import com.example.hjp.v5char.V5CharacterizationFixture.ALICE_PHONE
import com.example.hjp.v5char.V5CharacterizationFixture.BRIAN
import com.example.hjp.v5char.V5CharacterizationFixture.BRIAN_EMAIL
import com.example.hjp.v5char.V5CharacterizationFixture.OUTSIDE_EMAIL
import com.example.hjp.v5char.V5CharacterizationFixture.calendar
import com.example.hjp.v5char.V5CharacterizationFixture.compose
import com.example.hjp.v5char.V5CharacterizationFixture.datetime
import com.example.hjp.v5char.V5CharacterizationFixture.dependencyFindings
import com.example.hjp.v5char.V5CharacterizationFixture.get
import com.example.hjp.v5char.V5CharacterizationFixture.search
import com.example.hjp.v5char.V5CharacterizationFixture.turn
import com.example.hjp.v5char.V5CharacterizationFixture.update
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The control group: contact actions that were done correctly.
 *
 * A rule that catches every unsafe action is worthless if it also catches the safe ones — that is the
 * shape of the v4 defect, and a repair that over-corrects is the same defect with the sign flipped.
 * So these are the traces the agent is *supposed* to produce, and the correct number of
 * contact-dependency findings for every one of them is zero.
 */
class V5VerifiedContactActionCharacterizationTest {

    @Test
    fun `a fresh read of the exact card verifies the compose that follows`() {
        val observed = turn(
            calls = listOf(search("검색"), get(ALICE), compose(to = ALICE_EMAIL)),
            question = "그 사람한테 메일 준비해줘",
            expectedRoute = "followup",
            depth = 2,
            selectedCardId = ALICE,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `a fresh read verifies a phone recipient too`() {
        val observed = turn(
            calls = listOf(get(ALICE), compose(to = ALICE_PHONE, channel = "sms", subject = null)),
            question = "문자 준비해줘",
            depth = 3,
            selectedCardId = ALICE,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `every attendee read in this turn makes the calendar call clean`() {
        val observed = turn(
            calls = listOf(
                search("검색"), get(ALICE), get(BRIAN), datetime(),
                calendar(attendees = listOf(BRIAN_EMAIL, ALICE_EMAIL)),
            ),
            question = "두 사람이랑 내일 일정 잡아줘",
            expectedRoute = null,
            depth = 2,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `a card edit after reading that exact card is clean`() {
        val observed = turn(
            calls = listOf(search("검색"), get(BRIAN), update(cardId = BRIAN)),
            question = "이 사람 메모 좀 바꿔줘",
            expectedRoute = "followup",
            depth = 2,
            selectedCardId = BRIAN,
            answer = "명함을 수정했습니다.",
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `a re-read after a correction verifies the action that follows it`() {
        val observed = turn(
            calls = listOf(
                search("첫 검색"), get(ALICE),
                search("다른 사람"), get(BRIAN), compose(to = BRIAN_EMAIL),
            ),
            question = "아니 다른 사람으로 다시 찾아서 메일 준비해줘",
            expectedRoute = "followup",
            depth = 2,
            selectedCardId = BRIAN,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `an address the user dictated in this turn is not a stored contact`() {
        val observed = turn(
            calls = listOf(compose(to = OUTSIDE_EMAIL)),
            question = "$OUTSIDE_EMAIL 로 메일 초안 하나 만들어줘",
            expectedRoute = null,
            depth = 1,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `an address the user dictated is not a lookup even when the store also holds it`() {
        val observed = turn(
            calls = listOf(compose(to = ALICE_EMAIL)),
            question = "$ALICE_EMAIL 주소로 메일 초안 만들어줘",
            expectedRoute = null,
            depth = 1,
            selectedCardId = BRIAN,
            previousFocusCardId = BRIAN,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `a subject or a body naming somebody is not a recipient`() {
        val observed = turn(
            calls = listOf(
                get(ALICE),
                compose(
                    to = ALICE_EMAIL,
                    subject = "$BRIAN_EMAIL 건 관련 안내",
                    body = "$BRIAN_EMAIL 님과 논의한 내용을 정리했습니다.",
                ),
            ),
            question = "메일 준비해줘",
            depth = 2,
            selectedCardId = ALICE,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `the new values a card edit writes are not the edit's target`() {
        val observed = turn(
            calls = listOf(
                get(ALICE),
                update(cardId = ALICE, updates = mapOf("email" to BRIAN_EMAIL)),
            ),
            question = "이 사람 이메일을 바꿔줘",
            depth = 2,
            selectedCardId = ALICE,
            answer = "명함을 수정했습니다.",
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `an action that ran before any read in an earlier turn is judged on this turn only`() {
        val observed = turn(
            calls = listOf(get(ALICE), calendar(attendees = listOf(ALICE_EMAIL))),
            question = "이 사람이랑 일정 잡아줘",
            expectedRoute = null,
            depth = 5,
            selectedCardId = ALICE,
            previousFocusCardId = ALICE,
        )
        assertEquals(
            emptyList<String>(),
            dependencyFindings(observed, listOf(V5CharacterizationFixture.verification(BRIAN, "1/4")))
                .map { it.detail },
        )
    }
}
