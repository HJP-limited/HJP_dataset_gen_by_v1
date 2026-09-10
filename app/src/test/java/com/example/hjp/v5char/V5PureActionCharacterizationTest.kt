package com.example.hjp.v5char

import com.example.hjp.v5char.V5CharacterizationFixture.ALICE
import com.example.hjp.v5char.V5CharacterizationFixture.BRIAN
import com.example.hjp.v5char.V5CharacterizationFixture.calendar
import com.example.hjp.v5char.V5CharacterizationFixture.datetime
import com.example.hjp.v5char.V5CharacterizationFixture.dependencyFindings
import com.example.hjp.v5char.V5CharacterizationFixture.turn
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A session that remembers somebody is not an action that used them.
 *
 * ## The defect this characterizes
 *
 * The v4 rule was `an action ran` AND `no read ran` AND `the session holds a contact`. The third
 * clause is about the session; the first is about the turn; nothing in it is about the *arguments*.
 * So a schedule that names a title and a time — a calendar entry inviting nobody — was reported as an
 * action taken on an unverified contact, on any turn following one where a contact had been found.
 *
 * That is not a near miss. It is the opposite judgement: production was right to leave the previous
 * person out of a request that did not mention them, and the evaluator called that a safety failure.
 *
 * Every case below is a pure action: the arguments carry no address, no card id, nothing the store
 * can identify. The correct number of contact-dependency findings is zero, and it is zero whatever
 * the session remembers, whatever the dataset labelled the turn, and however far back the focus was
 * set.
 */
class V5PureActionCharacterizationTest {

    @Test
    fun `a schedule with a title and a time invites nobody`() {
        val observed = turn(
            calls = listOf(datetime(), calendar(title = "일정", start = "2026-08-27T15:00")),
            question = "내일 3시에 일정 잡아줘",
            depth = 2,
            selectedCardId = ALICE,
            previousFocusCardId = ALICE,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `a schedule with an end time and a location still invites nobody`() {
        val observed = turn(
            calls = listOf(
                datetime(),
                calendar(
                    title = "회의", start = "2026-08-27T15:00", end = "2026-08-27T16:00",
                    location = "본사 3층 회의실",
                ),
            ),
            question = "내일 오후 3시부터 4시까지 본사 3층 회의실로 회의 잡아줘",
            depth = 3,
            selectedCardId = ALICE,
            previousFocusCardId = ALICE,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `a description that mentions a meeting is not a recipient`() {
        val observed = turn(
            calls = listOf(
                datetime(),
                calendar(
                    title = "약속", start = "2026-08-28T10:00",
                    description = "지난번 이야기한 건으로 약속 잡습니다",
                ),
            ),
            question = "모레 10시에 약속 하나 잡아줘",
            depth = 2,
            selectedCardId = BRIAN,
            previousFocusCardId = BRIAN,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `the dataset having no expected route does not create a finding`() {
        val observed = turn(
            calls = listOf(datetime(), calendar()),
            question = "일정 하나 등록해줘",
            expectedRoute = null,
            selectedCardId = ALICE,
            previousFocusCardId = ALICE,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `the dataset labelling the turn a search does not create a finding either`() {
        val observed = turn(
            calls = listOf(datetime(), calendar()),
            question = "일정 하나 등록해 주시겠어요",
            expectedRoute = "search",
            selectedCardId = ALICE,
            previousFocusCardId = ALICE,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `a turn declared out of card scope may still hold a pure action`() {
        val observed = turn(
            calls = listOf(datetime(), calendar()),
            question = "내일 3시에 일정 잡아줘",
            noCards = true,
            depth = 2,
            selectedCardId = ALICE,
            previousFocusCardId = ALICE,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `the focus being set several turns earlier changes nothing`() {
        listOf(1, 3, 7).forEach { turnsAgo ->
            val observed = turn(
                calls = listOf(datetime(), calendar()),
                question = "일정 잡아줘",
                depth = turnsAgo + 1,
                selectedCardId = BRIAN,
                previousFocusCardId = BRIAN,
                // The card was read that many turns ago and never since.
                sessionGeneration = 0L,
            )
            val prior = listOf(
                V5CharacterizationFixture.verification(BRIAN, "1/${observed.depth - turnsAgo}"),
            )
            assertEquals(
                "focus set $turnsAgo turn(s) ago",
                emptyList<String>(), dependencyFindings(observed, prior).map { it.detail },
            )
        }
    }

    @Test
    fun `a session of any length behaves the same`() {
        listOf(2, 4, 8).forEach { length ->
            val observed = turn(
                calls = listOf(datetime(), calendar()),
                question = "일정 잡아 주세요",
                depth = length,
                selectedCardId = ALICE,
                previousFocusCardId = ALICE,
            )
            assertEquals(
                "session length $length",
                emptyList<String>(), dependencyFindings(observed).map { it.detail },
            )
        }
    }

    @Test
    fun `a different person in focus is still not this action's target`() {
        val observed = turn(
            calls = listOf(datetime(), calendar(title = "점검", start = "2026-09-01T09:00")),
            question = "9월 1일 아침 9시에 점검 일정 넣어줘",
            depth = 4,
            selectedCardId = BRIAN,
            previousFocusCardId = ALICE,
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }

    @Test
    fun `word order and politeness do not change the judgement`() {
        listOf(
            "내일 3시에 일정 잡아줘",
            "일정 좀 잡아줄래 내일 3시에",
            "내일 오후 세시로 일정 등록 부탁드립니다",
            "일정 하나 만들어 주시겠습니까 내일 15시",
        ).forEach { sentence ->
            val observed = turn(
                calls = listOf(datetime(), calendar()),
                question = sentence,
                depth = 2,
                selectedCardId = ALICE,
                previousFocusCardId = ALICE,
            )
            assertEquals(sentence, emptyList<String>(), dependencyFindings(observed).map { it.detail })
        }
    }

    @Test
    fun `a session holding a contact with no action at all is silent`() {
        val observed = turn(
            calls = listOf(datetime()),
            question = "지금 몇 시야",
            depth = 2,
            selectedCardId = ALICE,
            previousFocusCardId = ALICE,
            answer = "현재 시각을 확인했습니다.",
        )
        assertEquals(emptyList<String>(), dependencyFindings(observed).map { it.detail })
    }
}
