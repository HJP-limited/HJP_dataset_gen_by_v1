package com.example.hjp.v5

import com.example.hjp.deviceeval.DeviceRunEvidence
import com.example.hjp.deviceeval.DeviceRunEvidenceV5
import com.example.hjp.deviceeval.DeviceRunEvidenceV5.RunMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Every rule RUN_D5 will keep, proved on this machine against a temporary directory.
 *
 * The device is where these rules matter and the worst place to find out they are wrong: an
 * instrumentation run that overwrites the previous result, or promotes a short one, has destroyed the
 * evidence it was supposed to produce, and no assertion afterwards can recover it. So
 * [DeviceRunEvidenceV5] is plain `java.io` with no Android in it, and the whole contract is exercised
 * here first.
 *
 * The v4 contract is untouched and still has its own tests. This suite additionally proves the two
 * cannot collide: different identifiers, different directories, and a v5 run that is pointed at a v4
 * directory refuses rather than writing there.
 */
class DeviceRunEvidenceV5ContractTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun paths(mode: RunMode) = DeviceRunEvidenceV5.Paths(temporary.root, mode).prepare()

    private val completeInventory = DeviceRunEvidenceV5.RunInventoryGate.Expected(
        scenarios = 139, turns = 386, kinds = 22,
        depthCounts = mapOf(1 to 139, 2 to 127, 3 to 65, 4 to 35, 5 to 10, 6 to 10),
    )

    private fun observed(
        scenarios: Int = 139,
        turns: Int = 386,
        kinds: Int = 22,
        depths: Map<Int, Int> = completeInventory.depthCounts,
        duplicates: List<String> = emptyList(),
        missing: List<String> = emptyList(),
        crashed: Boolean = false,
    ) = DeviceRunEvidenceV5.RunInventoryGate.Observed(
        scenarios, turns, kinds, depths, duplicates, missing, crashed,
    )

    // ---- naming and separation -----------------------------------------------------------------------

    @Test
    fun `smoke and official carry different run identifiers`() {
        assertNotEquals(RunMode.SMOKE.runId, RunMode.OFFICIAL.runId)
        assertTrue(RunMode.OFFICIAL.runId.contains("RUN_D5"))
        assertTrue(RunMode.SMOKE.runId.contains("SMOKE"))
    }

    @Test
    fun `the v5 identifiers are not the v4 identifiers`() {
        assertNotEquals(DeviceRunEvidence.RunMode.OFFICIAL.runId, RunMode.OFFICIAL.runId)
        assertNotEquals(DeviceRunEvidence.RunMode.SMOKE.runId, RunMode.SMOKE.runId)
        assertFalse(RunMode.OFFICIAL.runId.contains("RUN_D4"))
        assertFalse(RunMode.SMOKE.runId.contains("_V4_"))
    }

    @Test
    fun `smoke and official write to different directories, and neither is a v4 directory`() {
        assertNotEquals(RunMode.SMOKE.directoryName, RunMode.OFFICIAL.directoryName)
        setOf(RunMode.SMOKE.directoryName, RunMode.OFFICIAL.directoryName).forEach { name ->
            assertFalse("$name collides with a v4 directory",
                name in DeviceRunEvidenceV5.FOREIGN_DIRECTORIES)
        }
        assertTrue(DeviceRunEvidence.RunMode.OFFICIAL.directoryName in DeviceRunEvidenceV5.FOREIGN_DIRECTORIES)
        assertTrue(DeviceRunEvidence.RunMode.SMOKE.directoryName in DeviceRunEvidenceV5.FOREIGN_DIRECTORIES)
    }

    @Test
    fun `an earlier version's directory is refused by name`() {
        DeviceRunEvidenceV5.FOREIGN_DIRECTORIES.forEach { foreign ->
            val thrown = runCatching {
                DeviceRunEvidenceV5.requireNotForeign(foreign)
            }.exceptionOrNull()
            assertTrue("writing into $foreign was allowed", thrown is IllegalArgumentException)
            assertTrue(
                "the refusal must say whose directory it is",
                thrown!!.message!!.contains("earlier"),
            )
        }
    }

    @Test
    fun `both v5 directories pass the same guard`() {
        RunMode.entries.forEach { mode ->
            DeviceRunEvidenceV5.requireNotForeign(mode.directoryName)
            assertEquals(mode.directoryName, paths(mode).directory.name)
        }
    }

    // ---- the scenario cap ----------------------------------------------------------------------------

    @Test
    fun `the official run refuses a scenario limit`() {
        val thrown = runCatching {
            DeviceRunEvidenceV5.requireScenarioLimitAllowed(RunMode.OFFICIAL, 5)
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(
            "the refusal must name the smoke run as the alternative",
            thrown!!.message!!.contains(RunMode.SMOKE.runId),
        )
    }

    @Test
    fun `the smoke run accepts a scenario limit`() {
        DeviceRunEvidenceV5.requireScenarioLimitAllowed(RunMode.SMOKE, 3)
    }

    @Test
    fun `a zero limit is not a limit`() {
        DeviceRunEvidenceV5.requireScenarioLimitAllowed(RunMode.OFFICIAL, 0)
    }

    // ---- one invocation --------------------------------------------------------------------------------

    @Test
    fun `the first official claim succeeds and the second is refused`() {
        val official = paths(RunMode.OFFICIAL)
        val first = DeviceRunEvidenceV5.OfficialRunGuard.claim(official, "{}", "now")
        assertTrue(first is DeviceRunEvidenceV5.ClaimResult.Claimed)
        assertEquals(1, DeviceRunEvidenceV5.OfficialRunGuard.invocationCount(official))

        val second = DeviceRunEvidenceV5.OfficialRunGuard.claim(official, "{}", "later")
        assertTrue(second is DeviceRunEvidenceV5.ClaimResult.Refused)
        assertEquals(
            "OFFICIAL_ALREADY_INVOKED",
            (second as DeviceRunEvidenceV5.ClaimResult.Refused).code,
        )
        assertEquals("the marker was rewritten", 1,
            DeviceRunEvidenceV5.OfficialRunGuard.invocationCount(official))
    }

    @Test
    fun `an existing result refuses the claim before the marker is even attempted`() {
        val official = paths(RunMode.OFFICIAL)
        official.result.writeText("{}")
        val claim = DeviceRunEvidenceV5.OfficialRunGuard.claim(official, "{}", "now")
        assertTrue(claim is DeviceRunEvidenceV5.ClaimResult.Refused)
        assertEquals(
            "OFFICIAL_RESULT_ALREADY_PRESENT",
            (claim as DeviceRunEvidenceV5.ClaimResult.Refused).code,
        )
        assertEquals("a refused run must not take the invocation", 0,
            DeviceRunEvidenceV5.OfficialRunGuard.invocationCount(official))
        assertEquals("{}", official.result.readText())
    }

    @Test
    fun `a stale partial refuses the claim and names the reason`() {
        val official = paths(RunMode.OFFICIAL)
        official.rawTurnsPartial.writeText("{\"scenario\":0}\n")
        val claim = DeviceRunEvidenceV5.OfficialRunGuard.claim(official, "{}", "now")
        assertEquals(
            "STALE_PARTIAL_PRESENT",
            (claim as DeviceRunEvidenceV5.ClaimResult.Refused).code,
        )
        assertTrue("the partial was deleted", official.rawTurnsPartial.isFile)
    }

    @Test
    fun `every refusal is logged and only the log is written`() {
        val official = paths(RunMode.OFFICIAL)
        official.status.writeText("{}")
        DeviceRunEvidenceV5.OfficialRunGuard.claim(official, "{}", "2026-08-26T00:00:00Z")
        DeviceRunEvidenceV5.OfficialRunGuard.claim(official, "{}", "2026-08-26T00:00:01Z")
        val log = official.refusalLog.readLines()
        assertEquals(2, log.size)
        assertTrue(log.all { it.contains("OFFICIAL_RESULT_ALREADY_PRESENT") })
        assertEquals(
            "a refused run wrote something other than the log",
            setOf("device_run_status.json", "refused_invocations.log"),
            official.directory.listFiles().orEmpty().map { it.name }.toSet(),
        )
    }

    @Test
    fun `a smoke run never takes the official invocation`() {
        val smoke = paths(RunMode.SMOKE)
        val official = paths(RunMode.OFFICIAL)
        assertFalse(RunMode.SMOKE.countsAsOfficialInvocation)
        DeviceRunEvidenceV5.DurableTurnStream(smoke).use { it.append("{\"scenario\":0}") }
        assertEquals(0, DeviceRunEvidenceV5.OfficialRunGuard.invocationCount(official))
        assertEquals(emptyList<String>(), official.existingFinalArtifacts())
    }

    // ---- streaming and promotion -------------------------------------------------------------------------

    @Test
    fun `turns are on disk before the run ends`() {
        val official = paths(RunMode.OFFICIAL)
        val stream = DeviceRunEvidenceV5.DurableTurnStream(official)
        stream.append("{\"scenario\":0,\"depth\":1}")
        stream.append("{\"scenario\":0,\"depth\":2}")
        assertEquals(2, official.rawTurnsPartial.readLines().size)
        assertFalse("nothing may be promoted yet", official.rawTurns.exists())
        stream.close()
    }

    @Test
    fun `a turn record may not contain a newline`() {
        val official = paths(RunMode.OFFICIAL)
        DeviceRunEvidenceV5.DurableTurnStream(official).use { stream ->
            val thrown = runCatching { stream.append("{\"a\":1}\n{\"b\":2}") }.exceptionOrNull()
            assertTrue(thrown is IllegalArgumentException)
        }
    }

    @Test
    fun `an incomplete run leaves its partial and promotes nothing`() {
        val official = paths(RunMode.OFFICIAL)
        val stream = DeviceRunEvidenceV5.DurableTurnStream(official)
        stream.append("{\"scenario\":0,\"depth\":1}")
        val gate = DeviceRunEvidenceV5.RunInventoryGate.check(
            completeInventory, observed(turns = 300, missing = listOf("300/386")), RunMode.OFFICIAL,
        )
        val failures = stream.promote(gate)
        assertTrue(failures.isNotEmpty())
        assertTrue("the partial must survive", official.rawTurnsPartial.isFile)
        assertFalse("nothing may be promoted", official.rawTurns.exists())
    }

    @Test
    fun `a complete run promotes by rename and leaves no partial`() {
        val official = paths(RunMode.OFFICIAL)
        val stream = DeviceRunEvidenceV5.DurableTurnStream(official)
        stream.append("{\"scenario\":0,\"depth\":1}")
        val gate = DeviceRunEvidenceV5.RunInventoryGate.check(
            completeInventory, observed(), RunMode.OFFICIAL,
        )
        assertEquals(emptyList<String>(), stream.promote(gate))
        assertTrue(official.rawTurns.isFile)
        assertEquals(emptyList<String>(), official.stalePartials())
        assertEquals("{\"scenario\":0,\"depth\":1}", official.rawTurns.readText().trim())
    }

    @Test
    fun `a stream refuses to start when the final record already exists`() {
        val official = paths(RunMode.OFFICIAL)
        official.rawTurns.writeText("{}\n")
        val thrown = runCatching { DeviceRunEvidenceV5.DurableTurnStream(official) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    // ---- the inventory gate --------------------------------------------------------------------------------

    @Test
    fun `every inventory mismatch is reported separately`() {
        listOf(
            observed(scenarios = 138) to "SCENARIO_COUNT",
            observed(turns = 385) to "TURN_COUNT",
            observed(kinds = 21) to "KIND_COUNT",
            observed(depths = mapOf(1 to 139)) to "DEPTH_INVENTORY",
            observed(duplicates = listOf("3/2")) to "DUPLICATE_TURNS",
            observed(missing = listOf("7/1")) to "MISSING_TURNS",
            observed(crashed = true) to "RUN_CRASHED",
        ).forEach { (state, code) ->
            val result = DeviceRunEvidenceV5.RunInventoryGate.check(
                completeInventory, state, RunMode.OFFICIAL,
            )
            assertTrue("$code was not reported: ${result.failures}",
                result.failures.any { it.startsWith(code) })
        }
    }

    @Test
    fun `a smoke run is not held to the official inventory`() {
        val result = DeviceRunEvidenceV5.RunInventoryGate.check(
            completeInventory, observed(scenarios = 3, turns = 9, kinds = 2, depths = mapOf(1 to 3)),
            RunMode.SMOKE,
        )
        assertTrue(result.complete)
    }

    @Test
    fun `a smoke run is still held to duplicates, gaps and crashes`() {
        listOf(
            observed(duplicates = listOf("1/1")) to "DUPLICATE_TURNS",
            observed(missing = listOf("1/2")) to "MISSING_TURNS",
            observed(crashed = true) to "RUN_CRASHED",
        ).forEach { (state, code) ->
            val result = DeviceRunEvidenceV5.RunInventoryGate.check(
                completeInventory, state, RunMode.SMOKE,
            )
            assertTrue("$code was not reported for a smoke run",
                result.failures.any { it.startsWith(code) })
        }
    }

    // ---- durable and atomic writes -----------------------------------------------------------------------------

    @Test
    fun `a durable write lands and can be read back`() {
        val target = File(temporary.root, "durable.json")
        DeviceRunEvidenceV5.writeDurably(target, "{\"a\":1}")
        assertEquals("{\"a\":1}", target.readText())
    }

    @Test
    fun `an atomic replace leaves no temporary behind`() {
        val target = File(temporary.root, "progress.json")
        DeviceRunEvidenceV5.replaceAtomically(target, "{\"turns\":1}")
        DeviceRunEvidenceV5.replaceAtomically(target, "{\"turns\":2}")
        assertEquals("{\"turns\":2}", target.readText())
        assertNull(
            "an atomic replace left a temporary file",
            temporary.root.listFiles()?.firstOrNull { it.name.endsWith(".partial") },
        )
    }

    @Test
    fun `the final artefact list is the three files a reader needs`() {
        assertEquals(
            listOf("raw_turns.jsonl", "device_result.json", "device_run_status.json"),
            DeviceRunEvidenceV5.FINAL_ARTIFACTS,
        )
    }
}
