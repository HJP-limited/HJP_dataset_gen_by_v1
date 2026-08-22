package com.example.hjp.integration

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.TrackedActionStatus
import com.hjp.tool.contact.BusinessCardRecord
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A request the agent never finished stops being resumable work after thirty minutes.
 *
 * Ported from the upstream structured-memory branch, which expires pending actions at
 * `MAX_PENDING_AGE_MILLIS = 30 * 60 * 1_000L`. Without it a turn that was interrupted — the app was
 * killed, the model failed, the user walked away — stays in the open-action list forever, and the
 * agent keeps treating a request from hours ago as something it still owes.
 *
 * ## The boundary, stated
 *
 * Upstream keeps an action while `now - updatedAt <= 30 minutes`, so at *exactly* thirty minutes the
 * action survives and expiry begins one millisecond later. That contract is adopted here rather than
 * reinvented, and it is asserted at 29:59, at 30:00 and at 30:00.001 so the edge is pinned rather
 * than implied.
 *
 * ## Time
 *
 * The clock is injected and moved by hand. Nothing here sleeps and nothing reads the wall clock: a
 * test that waits thirty minutes is not a test anyone will run.
 */
class PendingActionExpiryTest {

    private val person = BusinessCardRecord(
        id = "E001", name = "표하윤", company = "너울건설", title = "안전관리",
        industry = "건설", email = "hayun@neoul.example.net", mobile = "010-6060-0001",
    )

    private val thirtyMinutes = 30L * 60 * 1_000

    /** A clock a test moves deliberately. */
    private class ManualClock(startMillis: Long) {
        private val now = AtomicLong(startMillis)
        val millis: () -> Long = { now.get() }
        fun advance(byMillis: Long) = now.addAndGet(byMillis)
    }

    private fun harness(clock: ManualClock) = MultiturnScenarioHarness(
        cards = listOf(person),
        sessionClockMillis = clock.millis,
    )

    /**
     * Opens a request that stays unfinished, moves the clock, and reports the open actions left.
     *
     * The request is one the agent cannot complete on its own — a compose with no recipient — so it
     * ends needing clarification and stays open. That is exactly the state expiry is about.
     */
    private fun openActionsAfter(advanceMillis: Long): List<TrackedActionStatus> = runBlocking {
        val clock = ManualClock(1_700_000_000_000)
        val harness = harness(clock)
        try {
            harness.turn("제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
            assertTrue(
                "the first turn must leave something open, or this test proves nothing",
                harness.session().conversationMemory.openActions.isNotEmpty(),
            )
            clock.advance(advanceMillis)
            // A later turn is what gives the session a chance to notice the age.
            harness.turn("고맙습니다.")
            harness.session().conversationMemory.openActions.map { it.status }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a pending action survives at twenty nine fifty nine`() {
        val open = openActionsAfter(thirtyMinutes - 1_000)
        assertTrue(
            "one second short of the limit is still live work: $open",
            open.isNotEmpty(),
        )
    }

    @Test
    fun `a pending action survives at exactly thirty minutes`() {
        // The contract is `age <= 30 minutes`, so the boundary itself is retained. Asserted rather
        // than left to whichever comparison the implementation happened to use.
        val open = openActionsAfter(thirtyMinutes)
        assertTrue(
            "the boundary is inclusive by contract: $open",
            open.isNotEmpty(),
        )
    }

    @Test
    fun `a pending action expires past thirty minutes`() {
        assertEquals(
            "one millisecond past the limit and it is no longer work the agent owes",
            emptyList<TrackedActionStatus>(),
            openActionsAfter(thirtyMinutes + 1),
        )
    }

    @Test
    fun `a long-expired action is gone too`() {
        assertEquals(
            emptyList<TrackedActionStatus>(),
            openActionsAfter(4 * thirtyMinutes),
        )
    }

    @Test
    fun `an expired action is still visible as history, just not resumable`() = runBlocking {
        val clock = ManualClock(1_700_000_000_000)
        val harness = harness(clock)
        try {
            harness.turn("제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
            clock.advance(thirtyMinutes + 1)
            harness.turn("고맙습니다.")

            val memory = harness.session().conversationMemory
            assertTrue(
                "the record stays, so the user can still ask what happened",
                memory.actions.any { it.status == TrackedActionStatus.EXPIRED },
            )
            assertTrue(
                "but it is not resumable",
                memory.openActions.isEmpty(),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `completed and failed history is not touched by expiry`() = runBlocking {
        // Expiry is about work still owed. A finished turn is history and stays exactly as it was,
        // however old it gets.
        val clock = ManualClock(1_700_000_000_000)
        val harness = harness(clock)
        try {
            harness.turn("${person.name} 명함 찾아줘.")
            // Follow the one action this turn opened, rather than the whole list — later turns add
            // their own records and would make the comparison meaningless.
            val lookup = harness.session().conversationMemory.actions
                .single { it.request.contains(person.name) }
            assertTrue("the lookup must have finished", !lookup.status.isOpen)

            clock.advance(10 * thirtyMinutes)
            harness.turn("고맙습니다.")

            val afterwards = harness.session().conversationMemory.actions
                .single { it.turnId == lookup.turnId }
            assertEquals(
                "a completed action is not pending and must not be aged out",
                lookup.status,
                afterwards.status,
            )
            assertEquals(
                "and its timestamp is not rewritten either",
                lookup.updatedAtEpochMillis,
                afterwards.updatedAtEpochMillis,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `an expired request is not resumed by a later reference`() = runBlocking {
        val clock = ManualClock(1_700_000_000_000)
        val harness = harness(clock)
        try {
            harness.turn("제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
            clock.advance(thirtyMinutes + 1)
            val resumed = harness.turn("아까 그거 계속해줘.")

            assertTrue(
                "an expired request must not be picked up and executed: " +
                    "${resumed.executedTools} / ${resumed.newComposeDrafts.map { it.to }}",
                resumed.newComposeDrafts.isEmpty(),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a new conversation drops the previous pending action outright`() = runBlocking {
        val clock = ManualClock(1_700_000_000_000)
        val harness = harness(clock)
        try {
            harness.turn("제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
            harness.reset()
            harness.turn("고맙습니다.")

            assertTrue(
                "a reset does not wait for a timeout",
                harness.session().conversationMemory.openActions.isEmpty(),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `the clock is the only thing that ages an action`() = runBlocking {
        // Without advancing time, any number of later turns must leave the pending action alone.
        val clock = ManualClock(1_700_000_000_000)
        val harness = harness(clock)
        try {
            harness.turn("제목은 안내, 내용은 확인 부탁드립니다 라고 메일 작성해줘.")
            repeat(3) { harness.turn("고맙습니다.") }

            assertTrue(
                "turns do not age anything; elapsed time does",
                harness.session().conversationMemory.openActions.isNotEmpty(),
            )
        } finally {
            harness.close()
        }
    }
}
