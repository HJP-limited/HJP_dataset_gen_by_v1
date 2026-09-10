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
 * The v4 device **smoke** run. Not a measurement, and it cannot be mistaken for one.
 *
 * Its job is to prove the device can do the work at all — the model loads, the store resolves a
 * name, a handful of scenarios complete — before anybody spends a 386-turn official run finding out
 * the model file was the wrong one.
 *
 * It is a separate class from [RyeongDeviceEvaluationV4Test] on purpose. In v3 one class did both
 * jobs, sharing the run identifier, the output directory and the file names, so the only thing
 * separating a five-scenario shakeout from the run of record was a number inside the result. Here the
 * two cannot collide: the smoke run writes `ryeong_device_eval_v4_smoke`, carries its own run
 * identifier, and never touches the official invocation marker.
 *
 * Opt-in: without `-e ryeongSmokeV4 true` this reports as skipped, so an ordinary
 * `connectedAndroidTest` never starts a device evaluation by accident.
 */
@RunWith(AndroidJUnit4::class)
class RyeongDeviceSmokeV4Test {

    private val args = InstrumentationRegistry.getArguments()
    private val enabled = args.getString("ryeongSmokeV4") == "true"

    /** How many scenarios to run. A smoke run is allowed a cap; the official run is not. */
    private val scenarioLimit = args.getString("ryeongScenarioLimit")?.toIntOrNull() ?: DEFAULT_LIMIT

    @Test
    fun ryeongSmokeOnDevice() {
        if (!enabled) {
            android.util.Log.i(TAG, "skipped: pass -e ryeongSmokeV4 true to run")
            return
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        require(scenarioLimit > 0) { "a smoke run must be capped; got $scenarioLimit" }

        val outcome = RyeongDeviceRunnerV4(
            context, DeviceRunEvidence.RunMode.SMOKE, scenarioLimit,
        ).run()

        android.util.Log.i(TAG, "smoke turns=${outcome.turnsRun} scenarios=${outcome.scenariosRun} " +
            "model_successes=${outcome.counters.modelInvocationSuccesses}")

        // The official namespace must be exactly as untouched as it was before this ran.
        val official = DeviceRunEvidence.Paths(
            context.getExternalFilesDir(null) ?: context.filesDir,
            DeviceRunEvidence.RunMode.OFFICIAL,
        )
        assertEquals(
            "the smoke run wrote into the official namespace",
            emptyList<String>(), official.existingFinalArtifacts(),
        )
        assertEquals(
            "the smoke run consumed the official invocation",
            0, DeviceRunEvidence.OfficialRunGuard.invocationCount(official),
        )

        assertEquals("cross-scenario leakage", 0, outcome.crossScenarioLeakage)
        assertEquals("observation disagreement", 0, outcome.observationDisagreements)
        assertTrue("no turn ran", outcome.turnsRun > 0)
        // A smoke run exists to find out whether the model is really there.
        assertTrue(
            "the model never produced anything during the smoke run",
            outcome.counters.actualModelExecuted,
        )
    }

    private companion object {
        const val TAG = "RyeongDeviceSmokeV4"
        const val DEFAULT_LIMIT = 3
    }
}
