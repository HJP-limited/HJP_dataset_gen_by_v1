package com.example.hjp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hjp.deviceeval.DeviceRunEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `RUN_D4` — the official v4 device evaluation, against the real model, exactly once.
 *
 * ## What makes this the run of record
 *
 *  - It is its own class. A smoke run cannot reach this code, and this code cannot be capped:
 *    passing `ryeongScenarioLimit` makes it fail before the first turn rather than quietly produce a
 *    short result carrying the official identifier.
 *  - It takes the invocation with an atomic file creation. A second execution — deliberate or by a
 *    retry loop — is refused and recorded in a refusal log, and the first run's evidence is never
 *    opened for writing.
 *  - Turn records are streamed to `raw_turns.jsonl.partial`, flushed and synced per turn, and
 *    promoted to their final name only after the 139 / 386 / 22 inventory and the depth census match
 *    exactly. A run that dies leaves its partial and a status that says it is partial.
 *  - Whether an actual model ran is read from `AgentRuntimeCounters`, not asserted. v3 wrote
 *    `"actual_model_executed": true` as a literal; here zero successful invocations is a validity
 *    failure, because the model is the thing being measured.
 *
 * Opt-in: without `-e ryeongDeviceRunV4 true` this reports as skipped.
 */
@RunWith(AndroidJUnit4::class)
class RyeongDeviceEvaluationV4Test {

    private val args = InstrumentationRegistry.getArguments()
    private val enabled = args.getString("ryeongDeviceRunV4") == "true"

    /**
     * Read only so it can be refused.
     *
     * Ignoring the argument would let an operator believe a cap had been applied; accepting it would
     * produce a five-scenario file wearing RUN_D4's name. Failing loudly is the only option that
     * leaves no ambiguity.
     */
    private val scenarioLimit = args.getString("ryeongScenarioLimit")?.toIntOrNull() ?: 0

    @Test
    fun ryeongOfficialRunD4OnDevice() {
        if (!enabled) {
            android.util.Log.i(TAG, "skipped: pass -e ryeongDeviceRunV4 true to run")
            return
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Before anything else, and before the invocation is claimed.
        DeviceRunEvidence.requireScenarioLimitAllowed(DeviceRunEvidence.RunMode.OFFICIAL, scenarioLimit)

        val outcome = RyeongDeviceRunnerV4(
            context, DeviceRunEvidence.RunMode.OFFICIAL, scenarioLimit = 0,
        ).run()

        val paths = DeviceRunEvidence.Paths(
            context.getExternalFilesDir(null) ?: context.filesDir,
            DeviceRunEvidence.RunMode.OFFICIAL,
        )
        android.util.Log.i(TAG, "RUN_D4 turns=${outcome.turnsRun} " +
            "validity=${outcome.validityFailures} promotion=${outcome.promotionFailures}")

        // Asserted after the evidence is on disk, so a failure still leaves a record to read.
        assertEquals("official invocations", 1, DeviceRunEvidence.OfficialRunGuard.invocationCount(paths))
        assertEquals("promotion failures", emptyList<String>(), outcome.promotionFailures)
        assertEquals("partial files remaining after a complete run", emptyList<String>(), paths.stalePartials())
        assertEquals("turns", RyeongDeviceRunnerV4.EXPECTED_TURNS, outcome.turnsRun)
        assertEquals("scenarios", RyeongDeviceRunnerV4.EXPECTED_SCENARIOS, outcome.scenariosRun)
        assertEquals("cross-scenario leakage", 0, outcome.crossScenarioLeakage)
        assertEquals("observation disagreement", 0, outcome.observationDisagreements)
        assertTrue(
            "an actual-model evaluation recorded no actual model invocation",
            outcome.counters.actualModelExecuted,
        )
        assertEquals("validity failures", emptyList<String>(), outcome.validityFailures)
    }

    private companion object {
        const val TAG = "RyeongDeviceEvalV4"
    }
}
