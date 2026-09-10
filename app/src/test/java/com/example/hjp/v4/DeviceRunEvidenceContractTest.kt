package com.example.hjp.v4

import com.example.hjp.deviceeval.DeviceRunEvidence
import com.example.hjp.deviceeval.DeviceRunEvidence.ClaimResult
import com.example.hjp.deviceeval.DeviceRunEvidence.RunMode
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §9.5 — every rule the device evidence contract promises, checked without a device.
 *
 * The contract is about files, and files behave the same in a temporary directory as they do in an
 * app's external storage. Because the instrumentation runner and this test compile the *same*
 * `DeviceRunEvidence` — it lives in a source directory both source sets include — a rule proved here
 * is proved for the run that will happen on the device, not for a host-only lookalike.
 *
 * Nothing here starts an emulator, installs anything, or touches adb.
 */
class DeviceRunEvidenceContractTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("v4-device-evidence", "").let {
            it.delete(); it.mkdirs(); it
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun paths(mode: RunMode) = DeviceRunEvidence.Paths(root, mode).prepare()

    private val now = "2026-08-26T00:00:00Z"
    private val marker = """{"schema":"test","run_id":"x"}"""

    // ---- separation --------------------------------------------------------------------------

    @Test
    fun `smoke and official never share a directory, a file or a run id`() {
        val smoke = paths(RunMode.SMOKE)
        val official = paths(RunMode.OFFICIAL)

        assertNotEquals(smoke.directory, official.directory)
        assertNotEquals(RunMode.SMOKE.runId, RunMode.OFFICIAL.runId)
        listOf(
            smoke.rawTurns to official.rawTurns,
            smoke.result to official.result,
            smoke.status to official.status,
            smoke.invocationMarker to official.invocationMarker,
        ).forEach { (a, b) -> assertNotEquals(a.absolutePath, b.absolutePath) }
    }

    @Test
    fun `a smoke run leaves the official namespace untouched and uninvoked`() {
        val smoke = paths(RunMode.SMOKE)
        DeviceRunEvidence.writeDurably(smoke.result, "{}")
        DeviceRunEvidence.writeDurably(smoke.status, "{}")
        DeviceRunEvidence.DurableTurnStream(smoke).use { it.append("""{"turn":1}""") }

        val official = paths(RunMode.OFFICIAL)
        assertEquals(emptyList<String>(), official.existingFinalArtifacts())
        assertEquals(emptyList<String>(), official.stalePartials())
        assertEquals(0, DeviceRunEvidence.OfficialRunGuard.invocationCount(official))
    }

    @Test
    fun `an official run may follow a smoke run`() {
        paths(RunMode.SMOKE).let { DeviceRunEvidence.writeDurably(it.result, "{}") }
        val claim = DeviceRunEvidence.OfficialRunGuard.claim(paths(RunMode.OFFICIAL), marker, now)
        assertTrue(claim.toString(), claim is ClaimResult.Claimed)
    }

    // ---- one shot ----------------------------------------------------------------------------

    @Test
    fun `the second official invocation is refused`() {
        val official = paths(RunMode.OFFICIAL)
        assertTrue(DeviceRunEvidence.OfficialRunGuard.claim(official, marker, now) is ClaimResult.Claimed)

        val second = DeviceRunEvidence.OfficialRunGuard.claim(official, marker, now)
        assertEquals("OFFICIAL_ALREADY_INVOKED", (second as ClaimResult.Refused).code)
        assertEquals(1, DeviceRunEvidence.OfficialRunGuard.invocationCount(official))
    }

    @Test
    fun `an existing result refuses the run without touching it`() {
        val official = paths(RunMode.OFFICIAL)
        DeviceRunEvidence.writeDurably(official.result, """{"original":true}""")
        val before = official.result.readText()

        val refusal = DeviceRunEvidence.OfficialRunGuard.claim(official, marker, now)
        assertEquals("OFFICIAL_RESULT_ALREADY_PRESENT", (refusal as ClaimResult.Refused).code)
        assertEquals("the refused run modified the preserved result", before, official.result.readText())
        assertFalse("a refused run must not claim the invocation", official.invocationMarker.exists())
    }

    @Test
    fun `each of the final artefacts alone is enough to refuse`() {
        DeviceRunEvidence.FINAL_ARTIFACTS.forEach { name ->
            val fresh = DeviceRunEvidence.Paths(
                File(root, "case-$name").apply { mkdirs() }, RunMode.OFFICIAL,
            ).prepare()
            DeviceRunEvidence.writeDurably(File(fresh.directory, name), "{}")
            val refusal = DeviceRunEvidence.OfficialRunGuard.claim(fresh, marker, now)
            assertTrue("$name did not refuse", refusal is ClaimResult.Refused)
        }
    }

    @Test
    fun `a stale partial refuses the run and is preserved`() {
        val official = paths(RunMode.OFFICIAL)
        DeviceRunEvidence.writeDurably(official.rawTurnsPartial, """{"turn":1}""" + "\n")
        val before = official.rawTurnsPartial.readText()

        val refusal = DeviceRunEvidence.OfficialRunGuard.claim(official, marker, now)
        assertEquals("STALE_PARTIAL_PRESENT", (refusal as ClaimResult.Refused).code)
        assertEquals(before, official.rawTurnsPartial.readText())
    }

    @Test
    fun `a refusal is recorded outside the official artefacts`() {
        val official = paths(RunMode.OFFICIAL)
        DeviceRunEvidence.OfficialRunGuard.claim(official, marker, now)
        DeviceRunEvidence.OfficialRunGuard.claim(official, marker, now)

        assertTrue(official.refusalLog.isFile)
        assertTrue(official.refusalLog.readText().contains("OFFICIAL_ALREADY_INVOKED"))
        assertEquals(emptyList<String>(), official.existingFinalArtifacts())
    }

    // ---- durability and atomic completion -----------------------------------------------------

    @Test
    fun `turns are on disk before the run ends`() {
        val official = paths(RunMode.OFFICIAL)
        val stream = DeviceRunEvidence.DurableTurnStream(official)
        (1..5).forEach { stream.append("""{"turn":$it}""") }
        // No close, no promote: this is what a process kill would leave behind.
        assertEquals(5, official.rawTurnsPartial.readLines().size)
        assertFalse("a final record appeared before completion", official.rawTurns.exists())
    }

    @Test
    fun `an incomplete run promotes nothing and keeps its partial`() {
        val official = paths(RunMode.OFFICIAL)
        val stream = DeviceRunEvidence.DurableTurnStream(official)
        (1..300).forEach { stream.append("""{"turn":$it}""") }

        val gate = DeviceRunEvidence.RunInventoryGate.check(
            expected(), observed(turns = 300, crashed = true), RunMode.OFFICIAL,
        )
        val failures = stream.promote(gate)

        assertTrue(failures.toString(), failures.isNotEmpty())
        assertFalse(official.rawTurns.exists())
        assertTrue(official.rawTurnsPartial.isFile)
        assertEquals(300, official.rawTurnsPartial.readLines().size)
    }

    @Test
    fun `a complete run promotes and leaves no partial`() {
        val official = paths(RunMode.OFFICIAL)
        val stream = DeviceRunEvidence.DurableTurnStream(official)
        (1..386).forEach { stream.append("""{"turn":$it}""") }

        val failures = stream.promote(
            DeviceRunEvidence.RunInventoryGate.check(expected(), observed(), RunMode.OFFICIAL),
        )

        assertEquals(emptyList<String>(), failures)
        assertTrue(official.rawTurns.isFile)
        assertEquals(386, official.rawTurns.readLines().size)
        assertEquals(emptyList<String>(), official.stalePartials())
    }

    @Test
    fun `a turn record may never span two lines`() {
        val stream = DeviceRunEvidence.DurableTurnStream(paths(RunMode.OFFICIAL))
        val error = runCatching { stream.append("{\"a\":1}\n{\"b\":2}") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `streaming refuses to start when a final record already exists`() {
        val official = paths(RunMode.OFFICIAL)
        DeviceRunEvidence.writeDurably(official.rawTurns, "")
        val error = runCatching { DeviceRunEvidence.DurableTurnStream(official) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    // ---- inventory ----------------------------------------------------------------------------

    @Test
    fun `every inventory mismatch blocks promotion`() {
        listOf(
            "short" to observed(turns = 385),
            "scenarios" to observed(scenarios = 138),
            "kinds" to observed(kinds = 21),
            "depths" to observed(depths = mapOf(1 to 1)),
            "duplicate" to observed(duplicates = listOf("12/2")),
            "missing" to observed(missing = listOf("40/3")),
            "crashed" to observed(crashed = true),
        ).forEach { (label, obs) ->
            val gate = DeviceRunEvidence.RunInventoryGate.check(expected(), obs, RunMode.OFFICIAL)
            assertFalse("$label did not block promotion", gate.complete)
        }
        assertTrue(
            DeviceRunEvidence.RunInventoryGate.check(expected(), observed(), RunMode.OFFICIAL).complete,
        )
    }

    @Test
    fun `a smoke run is not held to the official inventory`() {
        val gate = DeviceRunEvidence.RunInventoryGate.check(
            expected(), observed(scenarios = 5, turns = 12, kinds = 3, depths = mapOf(1 to 5)),
            RunMode.SMOKE,
        )
        assertTrue(gate.failures.toString(), gate.complete)
    }

    // ---- the scenario cap ----------------------------------------------------------------------

    @Test
    fun `an official run refuses a scenario limit before doing anything`() {
        val error = runCatching {
            DeviceRunEvidence.requireScenarioLimitAllowed(RunMode.OFFICIAL, 5)
        }.exceptionOrNull()
        assertTrue(error.toString(), error is IllegalStateException)
        DeviceRunEvidence.requireScenarioLimitAllowed(RunMode.OFFICIAL, 0)
        DeviceRunEvidence.requireScenarioLimitAllowed(RunMode.SMOKE, 5)
    }

    // ---- atomic replace -------------------------------------------------------------------------

    @Test
    fun `progress status is replaced whole, never appended to`() {
        val official = paths(RunMode.OFFICIAL)
        DeviceRunEvidence.replaceAtomically(official.progress, """{"turns":1}""")
        DeviceRunEvidence.replaceAtomically(official.progress, """{"turns":2}""")
        assertEquals("""{"turns":2}""", official.progress.readText())
        assertEquals(emptyList<String>(), official.stalePartials())
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun expected() = DeviceRunEvidence.RunInventoryGate.Expected(
        scenarios = 139, turns = 386, kinds = 22, depthCounts = DEPTHS,
    )

    private fun observed(
        scenarios: Int = 139,
        turns: Int = 386,
        kinds: Int = 22,
        depths: Map<Int, Int> = DEPTHS,
        duplicates: List<String> = emptyList(),
        missing: List<String> = emptyList(),
        crashed: Boolean = false,
    ) = DeviceRunEvidence.RunInventoryGate.Observed(
        scenarios, turns, kinds, depths, duplicates, missing, crashed,
    )

    private companion object {
        /** Turns per depth in the frozen v2 set; sums to 386. */
        val DEPTHS = mapOf(1 to 139, 2 to 127, 3 to 65, 4 to 35, 5 to 10, 6 to 10)
    }
}
