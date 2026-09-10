package com.example.hjp.v4char

import com.example.hjp.MultiturnScenarioHarness
import com.example.hjp.v4.ScriptedModel
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.6 — what the actual Gemma artifact does that the deterministic router never does.
 *
 * The desktop run showed the model calling `get_contact`, receiving the card, and then writing a
 * sentence *about* the card instead of calling the tool the request needed. The turn was recorded as
 * complete, and nothing was ever opened. A local router cannot produce that shape, so it is
 * reproduced here with a scripted boundary: the production kernel, workflow session, registry,
 * validator and executor all run for real; only the model's replies are written down in advance.
 *
 * Seven shapes are covered, and the last four are the ones a naive "just ask again" repair breaks:
 * repeating a side effect, acting on a stale target, acting without a required slot, and acting after
 * the user cancelled.
 */
class V4ContinuationCharacterizationTest {

    private val anchor = V4Cards.ANCHOR
    private val second = V4Cards.SECOND

    private fun scripted(script: List<ScriptedModel.Step>, vararg cards: BusinessCardRecord) =
        ScriptedModel(script).let { model ->
            model to MultiturnScenarioHarness(
                cards = if (cards.isEmpty()) listOf(anchor) else cards.toList(),
                modelOverride = model,
            )
        }

    // ---- 1. prose after get_contact, with everything needed present ------------------------------

    @Test
    fun `prose after get_contact does not count as a finished compose request`() = runBlocking {
        val (model, h) = scripted(listOf(
            ScriptedModel.search(anchor.name),
            ScriptedModel.get(anchor.id),
            ScriptedModel.prose("${anchor.name}님의 명함을 확인했습니다."),
            ScriptedModel.compose(anchor.email),
        ))
        val turn = h.turn("${anchor.name}에게 ${V4Phrasing.MAIL_REQUESTS.first()}")

        assertTrue(
            "the boundary was never asked to finish the workflow: ${model.received}",
            model.received.size > 3,
        )
        assertTrue(
            "the turn ended in prose and was still recorded as a completed compose",
            turn.newComposeDrafts.isNotEmpty() ||
                turn.outcomeType != TurnOutcomeType.COMPOSE_OPENED,
        )
        h.close()
    }

    @Test
    fun `prose after get_contact does not count as a finished calendar request`() = runBlocking {
        val (_, h) = scripted(listOf(
            ScriptedModel.search(anchor.name),
            ScriptedModel.get(anchor.id),
            ScriptedModel.prose("${anchor.name}님의 일정을 확인했습니다."),
            ScriptedModel.calendar("2027-05-06T16:00", "2027-05-06T17:00", anchor.email),
        ))
        val turn = h.turn("${anchor.name}과 ${V4Phrasing.CALENDAR_REQUESTS.first()}")
        assertTrue(
            "prose was accepted as a created event",
            turn.newCalendarDrafts.isNotEmpty() ||
                turn.outcomeType != TurnOutcomeType.CALENDAR_OPENED,
        )
        h.close()
    }

    @Test
    fun `prose after get_contact does not count as a finished card update`() = runBlocking {
        val (_, h) = scripted(listOf(
            ScriptedModel.search(anchor.name),
            ScriptedModel.get(anchor.id, purpose = "display"),
            ScriptedModel.prose("메모를 확인했습니다."),
            ScriptedModel.update(anchor.id, "재검토"),
        ))
        val turn = h.turn("${anchor.name} ${V4Phrasing.UPDATE_REQUESTS.first()}")
        assertTrue(
            "prose was accepted as a written card",
            turn.executedTools.contains("update_business_card") ||
                turn.outcomeType != TurnOutcomeType.UPDATE_COMPLETED,
        )
        h.close()
    }

    // ---- 2. the repair must not make things worse -----------------------------------------------

    /** A side effect that already succeeded is never run twice by the repair. */
    @Test
    fun `a repair never repeats a side effect that already succeeded`() = runBlocking {
        val (_, h) = scripted(listOf(
            ScriptedModel.search(anchor.name),
            ScriptedModel.get(anchor.id),
            ScriptedModel.compose(anchor.email),
            ScriptedModel.prose("메일 작성 화면을 열었습니다."),
            ScriptedModel.compose(anchor.email),
        ))
        val turn = h.turn("${anchor.name}에게 ${V4Phrasing.MAIL_REQUESTS.first()}")
        assertEquals("the same compose ran twice", 1, turn.newComposeDrafts.size)
        h.close()
    }

    /** A terminal tool aimed at somebody this turn never verified is refused. */
    @Test
    fun `a terminal tool aimed at an unverified target is refused`() = runBlocking {
        val (_, h) = scripted(
            listOf(
                ScriptedModel.search(anchor.name),
                ScriptedModel.get(anchor.id),
                ScriptedModel.prose("확인했습니다."),
                // aimed at somebody this turn never read
                ScriptedModel.compose(second.email),
            ),
            anchor, second,
        )
        val turn = h.turn("${anchor.name}에게 ${V4Phrasing.MAIL_REQUESTS.first()}")
        assertTrue(
            "a compose reached an address this turn never verified: ${turn.newComposeDrafts.map { it.to }}",
            turn.newComposeDrafts.none { it.to == second.email },
        )
        h.close()
    }

    /** A terminal tool attempted with a required slot missing ends as a clarification. */
    @Test
    fun `a terminal tool attempted without a required slot ends as a clarification`() = runBlocking {
        val (_, h) = scripted(
            listOf(
                ScriptedModel.search(V4Cards.NO_EMAIL.name),
                ScriptedModel.get(V4Cards.NO_EMAIL.id),
                ScriptedModel.prose("명함을 확인했습니다."),
            ),
            V4Cards.NO_EMAIL,
        )
        val turn = h.turn("${V4Cards.NO_EMAIL.name}에게 ${V4Phrasing.MAIL_REQUESTS.first()}")
        assertEquals(0, turn.newComposeDrafts.size)
        assertEquals(TurnOutcomeType.CLARIFICATION_REQUIRED, turn.outcomeType)
        h.close()
    }

    /** After a cancellation the repair must not resurrect the action. */
    @Test
    fun `no terminal tool runs after the user cancels`() = runBlocking {
        val model = ScriptedModel(listOf(
            ScriptedModel.search(anchor.name),
            ScriptedModel.get(anchor.id, purpose = "display"),
            ScriptedModel.prose("메모를 확인했습니다."),
            ScriptedModel.update(anchor.id, "재검토"),
        ))
        val h = MultiturnScenarioHarness(
            cards = listOf(anchor), confirmUpdates = false, modelOverride = model,
        )
        val turn = h.turn("${anchor.name} ${V4Phrasing.UPDATE_REQUESTS.first()}")
        assertTrue(
            "a card was written after the user declined: ${turn.executedTools}",
            "update_business_card" !in turn.executedTools,
        )
        h.close()
    }

    /** A repair that fails ends the turn as unfinished rather than as a success. */
    @Test
    fun `a repair that fails ends the turn without claiming success`() = runBlocking {
        val (_, h) = scripted(listOf(
            ScriptedModel.search(anchor.name),
            ScriptedModel.get(anchor.id),
            ScriptedModel.prose("확인했습니다."),
            ScriptedModel.prose("확인했습니다."),
            ScriptedModel.prose("확인했습니다."),
        ))
        val turn = h.turn("${anchor.name}에게 ${V4Phrasing.MAIL_REQUESTS.first()}")
        assertEquals(0, turn.newComposeDrafts.size)
        assertTrue(
            "an unfinished turn was typed as a completed compose: ${turn.outcomeType}",
            turn.outcomeType != TurnOutcomeType.COMPOSE_OPENED,
        )
        h.close()
    }

    /** The plain chain still works end to end: search -> get -> terminal. */
    @Test
    fun `the ordinary chain still completes`() = runBlocking {
        val (_, h) = scripted(listOf(
            ScriptedModel.search(anchor.name),
            ScriptedModel.get(anchor.id),
            ScriptedModel.compose(anchor.email),
            ScriptedModel.prose("메일 작성 화면을 열었습니다."),
        ))
        val turn = h.turn("${anchor.name}에게 ${V4Phrasing.MAIL_REQUESTS.first()}")
        assertEquals(anchor.email, turn.newComposeDrafts.single().to)
        h.close()
    }
}
