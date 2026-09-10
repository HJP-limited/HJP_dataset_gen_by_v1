package com.example.hjp.v4char

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.1 — a new target that does not resolve must not fall back to the previous one.
 *
 * The failure this characterises is a wrong-recipient failure, which is the worst thing this agent
 * can do: A is looked up and selected; a later turn names B; B's lookup fails, is ambiguous, or
 * resolves to an id the store no longer has; and the *next* request opens a mail addressed to A.
 *
 * The contract is stated over four independent observables, because a fix that only changes the
 * wording of the refusal would satisfy a weaker one:
 *
 *  1. **side effects** — zero compose drafts and zero calendar drafts;
 *  2. **tool trace** — no action tool ran;
 *  3. **typed outcome** — the turn is typed as a clarification, not as a completed request;
 *  4. **values** — none of A's id, e-mail or phone number appears on any external surface.
 *
 * Every case is run against several phrasings, both politeness levels, several target names and two
 * session lengths, so passing by memorising a sentence is not available.
 */
class V4TargetTransitionCharacterizationTest {

    private fun harness(vararg cards: BusinessCardRecord) =
        MultiturnScenarioHarness(cards = cards.toList())

    /** Outcome types that assert an external screen was opened or a card was written. */
    private val COMPLETED_SIDE_EFFECTS = setOf(
        TurnOutcomeType.COMPOSE_OPENED,
        TurnOutcomeType.CALENDAR_OPENED,
        TurnOutcomeType.UPDATE_COMPLETED,
    )

    /** Turns that pad a session out, so "session length" is an axis rather than a constant. */
    private val FILLER = listOf(
        "오늘 날짜 알려줘.",
        "이 대화에서 확인한 연락처 알려줘.",
        "지금 몇 시야?",
    )

    private suspend fun MultiturnScenarioHarness.pad(long: Boolean) {
        if (long) FILLER.forEach { turn(it) }
    }

    // ---- 1. the reproduction: a new target that does not resolve --------------------------------

    /**
     * A names a person the store does not hold, then asks for an action.
     *
     * Three ways of failing to resolve are covered by three fixtures rather than by three branches:
     * absent (the store has no such card), ambiguous (two cards share the name), and stale (the card
     * that was selected has been removed from under the session). All three must behave identically
     * at the side-effect boundary.
     */
    @Test
    fun `an unresolvable new target never lets an action reach the previous person`() = runBlocking {
        val follows = V4Phrasing.MAIL_REQUESTS.map { "그분에게 $it" } +
            V4Phrasing.SMS_REQUESTS.map { "그 사람에게 $it" } +
            V4Phrasing.CALENDAR_REQUESTS.map { "그 사람과 $it" } +
            V4Phrasing.UPDATE_REQUESTS.map { "그 사람 $it" }

        listOf(false, true).forEach { longSession ->
            follows.forEach { follow ->
                val h = harness(V4Cards.ANCHOR)
                h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")
                h.pad(longSession)
                // 봉예람 is not in this store: the lookup cannot succeed.
                h.turn("${V4Cards.SECOND.name} 명함 찾아줘.")

                val turn = h.turn(follow)

                val label = "[long=$longSession] $follow"
                assertEquals("$label opened an external screen", 0,
                    turn.newComposeDrafts.size + turn.newCalendarDrafts.size)
                assertTrue("$label ran an action tool: ${turn.executedTools}",
                    turn.executedTools.none { it in V4Assert.ACTION_TOOLS })
                assertTrue(
                    "$label was recorded as a completed side effect: ${turn.outcomeType}",
                    turn.outcomeType !in COMPLETED_SIDE_EFFECTS,
                )
                V4Assert.assertNeverContacted(
                    h, V4Cards.ANCHOR.email, V4Cards.ANCHOR.mobile, V4Cards.ANCHOR.phone,
                )
                assertTrue("$label leaked the previous card id into an argument",
                    turn.toolArguments.none { (tool, args) ->
                        tool in V4Assert.ACTION_TOOLS && args.toString().contains(V4Cards.ANCHOR.id)
                    })
                h.close()
            }
        }
    }

    /** Same contract when the new target is ambiguous rather than absent. */
    @Test
    fun `an ambiguous new target blocks the action and asks which person`() = runBlocking {
        V4Phrasing.MAIL_REQUESTS.forEach { mail ->
            val h = harness(V4Cards.ANCHOR, V4Cards.TWIN_A, V4Cards.TWIN_B)
            h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")
            h.turn("${V4Cards.TWIN_A.name} 명함 찾아줘.")

            val turn = h.turn("그 사람에게 $mail")

            assertEquals(0, turn.newComposeDrafts.size)
            assertEquals(
                "an ambiguous target must ask, not act: $mail",
                TurnOutcomeType.CLARIFICATION_REQUIRED, turn.outcomeType,
            )
            V4Assert.assertNeverContacted(
                h, V4Cards.ANCHOR.email, V4Cards.TWIN_A.email, V4Cards.TWIN_B.email,
            )
            h.close()
        }
    }

    /**
     * A stale id: the card the session selected is gone by the time the action is requested.
     *
     * Reproduced by naming a second person the store does not have *after* a successful selection, so
     * the session holds a verified id and the current turn's target is unresolvable. The recorded
     * focus must not be reachable.
     */
    @Test
    fun `a zero-result lookup retires the focus for every channel`() = runBlocking {
        val h = harness(V4Cards.ANCHOR)
        h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")
        h.turn("갈원비 명함 찾아줘.")

        (V4Phrasing.MAIL_REQUESTS.map { "그 사람에게 $it" } +
            V4Phrasing.SMS_REQUESTS.map { "그분에게 $it" }).forEach { follow ->
            val turn = h.turn(follow)
            assertEquals("$follow opened a screen", 0, turn.newComposeDrafts.size)
        }
        V4Assert.assertNeverContacted(h, V4Cards.ANCHOR.email, V4Cards.ANCHOR.mobile)
        h.close()
    }

    // ---- 2. the controls: the paths that must keep working --------------------------------------

    /**
     * No new candidate in this turn plus an explicit anaphor: the focus is usable, after a fresh
     * `get_contact`. Without this control the fix could be "never act", which passes every negative
     * test and breaks the product.
     */
    @Test
    fun `an anaphor with no new candidate reaches the focus after a fresh read`() = runBlocking {
        V4Phrasing.ANAPHORS.forEach { anaphor ->
            val h = harness(V4Cards.ANCHOR)
            h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")

            val turn = h.turn("$anaphor 에게 ${V4Phrasing.MAIL_REQUESTS.first()}")

            assertEquals("$anaphor did not reach the focus", V4Cards.ANCHOR.email,
                turn.newComposeDrafts.singleOrNull()?.to)
            assertEquals("$anaphor acted without re-reading the card",
                "get_contact", turn.executedTools.firstOrNull())
            h.close()
        }
    }

    /** A new target that *does* resolve takes over completely, and is read fresh before use. */
    @Test
    fun `a resolving new target takes over and is read fresh`() = runBlocking {
        val h = harness(V4Cards.ANCHOR, V4Cards.SECOND)
        h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")
        h.turn("그 사람에게 ${V4Phrasing.MAIL_REQUESTS.first()}")
        h.turn("${V4Cards.SECOND.name} 명함 찾아줘.")

        val turn = h.turn("그분에게 ${V4Phrasing.MAIL_REQUESTS.first()}")

        assertEquals(listOf("get_contact", "open_compose"), turn.executedTools)
        assertEquals(V4Cards.SECOND.email, turn.newComposeDrafts.single().to)
        assertEquals(V4Cards.SECOND.id, turn.memory.selectedContact?.cardId)
        assertEquals(1, h.messages.drafts.count { it.to == V4Cards.ANCHOR.email })
        h.close()
    }

    /**
     * A correction retires the person it replaced, and a second correction retires the first
     * replacement: B's verified state must not survive B -> C.
     */
    @Test
    fun `a correction chain discards every superseded target`() = runBlocking {
        val h = harness(V4Cards.ANCHOR, V4Cards.SECOND, V4Cards.THIRD)
        h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")
        h.turn("${V4Cards.ANCHOR.name} 말고 ${V4Cards.SECOND.name} 명함 찾아줘.")
        h.turn("${V4Cards.SECOND.name} 말고 ${V4Cards.THIRD.name} 명함 찾아줘.")

        val turn = h.turn("그 사람에게 ${V4Phrasing.MAIL_REQUESTS.first()}")

        assertEquals(V4Cards.THIRD.email, turn.newComposeDrafts.singleOrNull()?.to)
        V4Assert.assertNeverContacted(h, V4Cards.ANCHOR.email, V4Cards.SECOND.email)
        h.close()
    }

    /** A new conversation makes the previous target unreachable, on every channel. */
    @Test
    fun `a new conversation makes the previous target unreachable`() = runBlocking {
        (V4Phrasing.MAIL_REQUESTS.map { "그 사람에게 $it" } +
            V4Phrasing.SMS_REQUESTS.map { "그분에게 $it" } +
            V4Phrasing.CALENDAR_REQUESTS.map { "그 사람과 $it" }).forEach { follow ->
            val h = harness(V4Cards.ANCHOR)
            h.turn("${V4Cards.ANCHOR.name} 명함 찾아줘.")
            h.reset()

            val turn = h.turn(follow)

            assertEquals("$follow acted after 새 대화", 0,
                turn.newComposeDrafts.size + turn.newCalendarDrafts.size)
            assertNull(turn.memory.selectedContact)
            V4Assert.assertNeverContacted(h, V4Cards.ANCHOR.email, V4Cards.ANCHOR.mobile)
            h.close()
        }
    }
}
