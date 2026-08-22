package com.example.hjp.eval.clock

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The instant a frozen replay pretends it is running at.
 *
 * A dataset that says "내일 오후 2시" has an expected answer only relative to some day. Held-out v2
 * and v3 wrote that day down — 2026-08-10 — and then let the harness resolve 내일 against the wall
 * clock, so the suites passed on exactly one calendar day and have failed every day since. That is
 * not a property of the agent; it is a property of the calendar, and a frozen artefact that decays
 * cannot be replayed to check anything.
 *
 * So the reference instant becomes an input. Production keeps the system clock — [SYSTEM] is the
 * default everywhere — and only a frozen replay supplies a fixed one, taken from the dataset's own
 * declared `reference_date` and timezone rather than from today.
 *
 * Nothing here changes an expected value, a threshold or a scoring rule. It changes which clock the
 * question is asked against.
 */
data class EvaluationClock(
    /** Epoch millis the replay runs at, or null to use the real system clock. */
    val fixedEpochMillis: Long?,
    val zoneId: ZoneId,
) {
    /** Millis supplier in the shape the tool plugins and the session manager already accept. */
    val millis: () -> Long
        get() = fixedEpochMillis?.let { fixed -> { fixed } } ?: System::currentTimeMillis

    val isFixed: Boolean get() = fixedEpochMillis != null

    /** The calendar day this clock reports, which is what a relative date resolves against. */
    fun today(): LocalDate =
        java.time.Instant.ofEpochMilli(millis()).atZone(zoneId).toLocalDate()

    fun describe(): String =
        if (isFixed) "fixed ${today()} (${zoneId.id}, epochMillis=$fixedEpochMillis)"
        else "system clock (${zoneId.id})"

    companion object {
        /** What production uses, and what every existing default resolves to. */
        fun system(zoneId: ZoneId = ZoneId.of("Asia/Seoul")) = EvaluationClock(null, zoneId)

        /**
         * The clock a frozen dataset declares.
         *
         * Midday rather than midnight on purpose: a replay pinned to 00:00 sits one second away from
         * being a different day under any rounding, and the datasets only ever assert on the date.
         */
        fun fixedAt(
            date: LocalDate,
            timeOfDay: LocalTime = LocalTime.NOON,
            zoneId: ZoneId = ZoneId.of("Asia/Seoul"),
        ) = EvaluationClock(
            LocalDateTime.of(date, timeOfDay).atZone(zoneId).toInstant().toEpochMilli(),
            zoneId,
        )
    }
}
