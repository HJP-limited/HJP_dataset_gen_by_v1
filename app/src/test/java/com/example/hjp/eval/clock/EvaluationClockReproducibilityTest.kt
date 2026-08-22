package com.example.hjp.eval.clock

import com.example.hjp.MultiturnScenarioHarness
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A frozen dataset pins the day it was written against; the machine replaying it does not stand
 * still. Four v3 assertions failed for no reason other than the calendar advancing a week past the
 * dataset's reference date — the agent was right and the harness was reading a different "today".
 *
 * These tests hold the property that fixes it: a replay's answer is a function of its declared
 * clock, not of the day someone happens to run it.
 */
class EvaluationClockReproducibilityTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    /** A relative-date request, so the answer moves if and only if "today" moves. */
    private fun tomorrowSchedule(clock: EvaluationClock): String? = runBlocking {
        val harness = MultiturnScenarioHarness(clock = clock)
        try {
            val record = harness.turn("내일 오후 3시에 회의 일정 잡아줘.")
            record.toolArguments
                .lastOrNull { it.first == "create_calendar_event" }
                ?.second?.get("start_time")?.jsonPrimitive?.content
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a fixed clock gives the same answer whatever day the replay runs`() {
        val reference = LocalDate.of(2026, 8, 10)
        // The same declared clock, constructed three times, as three separate replays would.
        val answers = (1..3).map { tomorrowSchedule(EvaluationClock.fixedAt(reference, zoneId = seoul)) }

        assertEquals(
            "a fixed clock must not produce varying answers: $answers",
            1,
            answers.distinct().size,
        )
        assertEquals(
            "with today pinned to $reference, 내일 is 2026-08-11 — this is the assertion that the " +
                "frozen v3 dataset encodes, and it must hold on any calendar day",
            "2026-08-11T15:00",
            answers.first(),
        )
    }

    @Test
    fun `moving the fixed clock is the only thing that moves the answer`() {
        val onReferenceDay = tomorrowSchedule(EvaluationClock.fixedAt(LocalDate.of(2026, 8, 10), zoneId = seoul))
        val aWeekLater = tomorrowSchedule(EvaluationClock.fixedAt(LocalDate.of(2026, 8, 17), zoneId = seoul))

        assertEquals("2026-08-11T15:00", onReferenceDay)
        assertEquals("2026-08-18T15:00", aWeekLater)
        assertNotEquals(onReferenceDay, aWeekLater)
    }

    @Test
    fun `the drift that broke the frozen v3 replay is exactly the clock, not the agent`() {
        // Reproduces the observed failure: expected 2026-08-11T14:00, actual 2026-08-18T14:00.
        // Same agent, same request, same code — only the clock differs, and the gap is the gap
        // between the dataset's reference date and the day of the run.
        val reference = LocalDate.of(2026, 8, 10)
        val driftedBy = 7L

        val atReference = tomorrowSchedule(EvaluationClock.fixedAt(reference, zoneId = seoul))!!
        val drifted = tomorrowSchedule(EvaluationClock.fixedAt(reference.plusDays(driftedBy), zoneId = seoul))!!

        val referenceDay = LocalDate.parse(atReference.substringBefore('T'))
        val driftedDay = LocalDate.parse(drifted.substringBefore('T'))
        assertEquals(
            "the difference between the two answers is the clock drift and nothing else",
            driftedBy,
            java.time.temporal.ChronoUnit.DAYS.between(referenceDay, driftedDay),
        )
        assertEquals(
            "time of day comes from the utterance, not the clock, so it must not move",
            atReference.substringAfter('T'),
            drifted.substringAfter('T'),
        )
    }

    @Test
    fun `time of day within the fixed day does not change a date-only answer`() {
        val reference = LocalDate.of(2026, 8, 10)
        val earlyMorning = tomorrowSchedule(
            EvaluationClock.fixedAt(reference, timeOfDay = LocalTime.of(0, 5), zoneId = seoul),
        )
        val lateEvening = tomorrowSchedule(
            EvaluationClock.fixedAt(reference, timeOfDay = LocalTime.of(23, 55), zoneId = seoul),
        )
        assertEquals(
            "any instant inside the fixed day is the same 'today'",
            earlyMorning,
            lateEvening,
        )
    }

    @Test
    fun `the zone is part of the clock, so today is well defined at the day boundary`() {
        // 2026-08-10 23:30 in Seoul is still 2026-08-10 there and already 2026-08-10 14:30 UTC,
        // but 2026-08-10 00:30 Seoul is 2026-08-09 UTC. Pinning the zone is what makes "today"
        // reproducible rather than dependent on the runner's locale.
        val nearMidnightSeoul = EvaluationClock.fixedAt(
            LocalDate.of(2026, 8, 10),
            timeOfDay = LocalTime.of(0, 30),
            zoneId = seoul,
        )
        assertEquals(LocalDate.of(2026, 8, 10), nearMidnightSeoul.today())

        val sameInstantInUtc = EvaluationClock(nearMidnightSeoul.fixedEpochMillis, ZoneId.of("UTC"))
        assertEquals(
            "the same instant is a different calendar day elsewhere — which is why the replay " +
                "declares its zone instead of inheriting the machine's",
            LocalDate.of(2026, 8, 9),
            sameInstantInUtc.today(),
        )
    }

    @Test
    fun `production keeps the system clock`() {
        val production = EvaluationClock.system()
        assertFalse(
            "only replays pin the clock; the shipping app must not",
            production.isFixed,
        )

        val before = System.currentTimeMillis()
        val observed = production.millis()
        val after = System.currentTimeMillis()
        assertTrue(
            "the system clock must read the real time, not a frozen one ($observed not in $before..$after)",
            observed in before..after,
        )
    }

    @Test
    fun `a fixed clock reads its declared instant`() {
        val clock = EvaluationClock.fixedAt(LocalDate.of(2026, 8, 10), zoneId = seoul)
        assertEquals(
            "a fixed clock does not advance between reads",
            clock.millis(),
            clock.millis(),
        )
        assertTrue(clock.isFixed)
        assertTrue(
            "a report has to be able to state the clock it ran under: ${clock.describe()}",
            clock.describe().contains("2026-08-10") && clock.describe().contains("Asia/Seoul"),
        )
    }

    @Test
    fun `the harness reaches the current-time tool through the injected clock`() {
        // If any component still called System.currentTimeMillis() directly, this would return
        // the real today and the assertion would fail on every day except the reference date.
        val clock = EvaluationClock.fixedAt(LocalDate.of(2026, 8, 10), zoneId = seoul)
        val answer = runBlocking {
            val harness = MultiturnScenarioHarness(clock = clock)
            try {
                harness.turn("오늘 며칠이야?").answer
            } finally {
                harness.close()
            }
        }
        assertTrue(
            "the datetime tool must read the injected clock, not the wall clock: $answer",
            answer.contains("2026") && answer.contains("08") && answer.contains("10"),
        )
    }
}
