package com.example.hjp.v6

import com.example.hjp.deviceeval.DeviceRunEvidenceV5
import com.example.hjp.deviceeval.DeviceRunEvidenceV6
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-run contract, proved on the host, before a device exists.
 *
 * `DeviceRunEvidenceV6` is compiled into both the instrumentation APK and these unit tests, so
 * every rule asserted here is the rule the device obeys. That matters more for v6 than it did for
 * v5: `RUN_D6` will run once, on hardware, against a real model, and a promotion rule discovered to
 * be wrong afterwards cannot be re-tested without spending the invocation.
 */
class DeviceRunEvidenceV6ContractTest {

    @Test
    fun `the official and smoke runs are different runs in every way that could collide`() {
        val official = DeviceRunEvidenceV6.RunMode.OFFICIAL
        val smoke = DeviceRunEvidenceV6.RunMode.SMOKE

        assertEquals(
            "RYEONG_PRODUCTION_COMPATIBILITY_V6_RUN_D6_DEVICE_ACTUAL_MODEL_BASELINE",
            official.runId,
        )
        assertEquals("RYEONG_PRODUCTION_COMPATIBILITY_V6_SMOKE_DEVICE", smoke.runId)
        assertEquals("ryeong_device_eval_v6_official", official.directoryName)
        assertEquals("ryeong_device_eval_v6_smoke", smoke.directoryName)

        assertTrue("only the official run consumes the invocation",
            official.countsAsOfficialInvocation)
        assertFalse("a smoke run must not consume the official invocation",
            smoke.countsAsOfficialInvocation)
        assertFalse("an official run may not be capped", official.scenarioLimitAllowed)
        assertTrue("a smoke run may be capped", smoke.scenarioLimitAllowed)
    }

    @Test
    fun `a v6 run refuses every earlier device namespace by name`() {
        listOf(
            "ryeong_device_eval_v3",
            "ryeong_device_eval_v4_smoke",
            "ryeong_device_eval_v4_official",
            "ryeong_device_eval_v5_smoke",
            "ryeong_device_eval_v5_official",
        ).forEach { name ->
            assertTrue("$name must be foreign to v6", name in DeviceRunEvidenceV6.FOREIGN_DIRECTORIES)
            val refused = runCatching { DeviceRunEvidenceV6.requireNotForeign(name) }.isFailure
            assertTrue("a v6 run must refuse to write into $name", refused)
        }
        // And v5's own list must not have grown a v6 entry: the frozen contract stays frozen.
        assertFalse(
            "the frozen v5 contract must not know about v6 directories",
            DeviceRunEvidenceV5.FOREIGN_DIRECTORIES.any { it.contains("v6") },
        )
    }

    @Test
    fun `an official run refuses a scenario limit and a smoke run accepts one`() {
        val refused = runCatching {
            DeviceRunEvidenceV6.requireScenarioLimitAllowed(DeviceRunEvidenceV6.RunMode.OFFICIAL, 5)
        }
        assertTrue("an official run must refuse a cap", refused.isFailure)
        assertTrue(
            "the refusal must name the smoke run as the alternative",
            refused.exceptionOrNull()?.message.orEmpty().contains("SMOKE_DEVICE"),
        )
        DeviceRunEvidenceV6.requireScenarioLimitAllowed(DeviceRunEvidenceV6.RunMode.OFFICIAL, 0)
        DeviceRunEvidenceV6.requireScenarioLimitAllowed(DeviceRunEvidenceV6.RunMode.SMOKE, 3)
    }

    @Test
    fun `the official invocation is taken once and every later attempt is refused and logged`() {
        val root = Files.createTempDirectory("v6-invocation").toFile()
        val paths = DeviceRunEvidenceV6.Paths(root, DeviceRunEvidenceV6.RunMode.OFFICIAL).prepare()

        val first = DeviceRunEvidenceV6.OfficialRunGuard.claim(paths, "first", "2026-01-01T00:00:00Z")
        assertTrue("the first claim must succeed", first is DeviceRunEvidenceV6.ClaimResult.Claimed)
        assertEquals(1, DeviceRunEvidenceV6.OfficialRunGuard.invocationCount(paths))

        val second = DeviceRunEvidenceV6.OfficialRunGuard.claim(paths, "second", "2026-01-01T00:01:00Z")
        assertTrue("a second claim must be refused",
            second is DeviceRunEvidenceV6.ClaimResult.Refused)
        assertEquals("INVOCATION_ALREADY_CLAIMED",
            (second as DeviceRunEvidenceV6.ClaimResult.Refused).code)
        assertEquals("the marker must still say what the first run wrote",
            "first", paths.invocationMarker.readText(Charsets.UTF_8))
        assertTrue("the refusal must be logged", paths.refusalLog.isFile)
        assertTrue("a refusal must not create a result", !paths.result.exists())
        root.deleteRecursively()
    }

    @Test
    fun `a directory holding a finished run reports that rather than a marker collision`() {
        val root = Files.createTempDirectory("v6-existing").toFile()
        val paths = DeviceRunEvidenceV6.Paths(root, DeviceRunEvidenceV6.RunMode.OFFICIAL).prepare()
        paths.rawTurns.writeText("{}\n", Charsets.UTF_8)

        val claim = DeviceRunEvidenceV6.OfficialRunGuard.claim(paths, "x", "2026-01-01T00:00:00Z")
        assertEquals("RUN_ALREADY_EXECUTED",
            (claim as DeviceRunEvidenceV6.ClaimResult.Refused).code)
        assertEquals("the existing record must be untouched", "{}\n",
            paths.rawTurns.readText(Charsets.UTF_8))
        root.deleteRecursively()
    }

    @Test
    fun `a stale partial refuses the run rather than being overwritten`() {
        val root = Files.createTempDirectory("v6-stale").toFile()
        val paths = DeviceRunEvidenceV6.Paths(root, DeviceRunEvidenceV6.RunMode.OFFICIAL).prepare()
        paths.rawTurnsPartial.writeText("{\"partial\":true}\n", Charsets.UTF_8)

        val claim = DeviceRunEvidenceV6.OfficialRunGuard.claim(paths, "x", "2026-01-01T00:00:00Z")
        assertEquals("STALE_PARTIAL_PRESENT",
            (claim as DeviceRunEvidenceV6.ClaimResult.Refused).code)
        assertEquals("the partial must be untouched", "{\"partial\":true}\n",
            paths.rawTurnsPartial.readText(Charsets.UTF_8))
        root.deleteRecursively()
    }

    @Test
    fun `a record is promoted only when its inventory matches, and never when it does not`() {
        val expected = DeviceRunEvidenceV6.RunInventoryGate.Expected(
            scenarios = 139, turns = 386, kinds = 22,
            depthCounts = mapOf(1 to 139, 2 to 127, 3 to 65, 4 to 35, 5 to 10, 6 to 10),
        )
        fun observed(
            scenarios: Int = 139, turns: Int = 386, kinds: Int = 22,
            depths: Map<Int, Int> = expected.depthCounts,
            duplicates: List<String> = emptyList(),
            missing: List<String> = emptyList(),
            crashed: Boolean = false,
            withoutSlotState: List<String> = emptyList(),
        ) = DeviceRunEvidenceV6.RunInventoryGate.Observed(
            scenarios, turns, kinds, depths, duplicates, missing, crashed, withoutSlotState,
        )

        val official = DeviceRunEvidenceV6.RunMode.OFFICIAL
        assertTrue("a complete official run must promote",
            DeviceRunEvidenceV6.RunInventoryGate.check(expected, observed(), official).complete)

        listOf(
            "a short run" to observed(turns = 385),
            "a run with a duplicate" to observed(duplicates = listOf("0/1")),
            "a run with a missing turn" to observed(missing = listOf("0/1")),
            "a crashed run" to observed(crashed = true),
            "a wrong depth census" to observed(depths = mapOf(1 to 386)),
            "a record with no slot state" to observed(withoutSlotState = listOf("0/1")),
        ).forEach { (why, bad) ->
            assertFalse(
                "$why must not be promoted",
                DeviceRunEvidenceV6.RunInventoryGate.check(expected, bad, official).complete,
            )
        }

        // A smoke run may be short — that is what a cap is — but never duplicated or crashed.
        val smoke = DeviceRunEvidenceV6.RunMode.SMOKE
        assertTrue("a capped smoke run is complete",
            DeviceRunEvidenceV6.RunInventoryGate.check(
                expected, observed(scenarios = 3, turns = 8, kinds = 2, depths = mapOf(1 to 3)),
                smoke,
            ).complete)
        assertFalse("a crashed smoke run is not complete",
            DeviceRunEvidenceV6.RunInventoryGate.check(expected, observed(crashed = true), smoke)
                .complete)
    }

    @Test
    fun `the raw record is durable per turn and refuses a malformed line`() {
        val root = Files.createTempDirectory("v6-stream").toFile()
        val paths = DeviceRunEvidenceV6.Paths(root, DeviceRunEvidenceV6.RunMode.SMOKE).prepare()

        DeviceRunEvidenceV6.DurableTurnStream(paths).use { stream ->
            stream.append("""{"scenario":0,"depth":1}""")
            // Readable from another handle *before* the stream is closed: that is what a sync buys.
            assertEquals(
                "the turn must be on disk before the run ends",
                1, paths.rawTurnsPartial.readLines(Charsets.UTF_8).size,
            )
            assertTrue("a newline inside a record must be refused",
                runCatching { stream.append("{\"a\":\"b\nc\"}") }.isFailure)
            assertTrue("a record that is not one object must be refused",
                runCatching { stream.append("""{"a":1}{"b":2}""") }.isFailure)
            stream.append("""{"scenario":0,"depth":2}""")
            assertEquals(2, stream.turnsWritten)
        }
        root.deleteRecursively()
    }

    @Test
    fun `a record whose inventory fails keeps its partial and produces no final record`() {
        val root = Files.createTempDirectory("v6-promote").toFile()
        val paths = DeviceRunEvidenceV6.Paths(root, DeviceRunEvidenceV6.RunMode.SMOKE).prepare()
        val stream = DeviceRunEvidenceV6.DurableTurnStream(paths)
        stream.append("""{"scenario":0,"depth":1}""")
        stream.close()

        val failed = DeviceRunEvidenceV6.RunInventoryGate.Result(listOf("RUN_CRASHED"))
        assertEquals(listOf("RUN_CRASHED"), stream.promote(failed))
        assertTrue("the partial must survive a refused promotion", paths.rawTurnsPartial.isFile)
        assertFalse("no final record may exist", paths.rawTurns.exists())

        assertEquals(emptyList<String>(),
            stream.promote(DeviceRunEvidenceV6.RunInventoryGate.Result(emptyList())))
        assertTrue("a passing gate promotes", paths.rawTurns.isFile)
        assertFalse("and the partial is gone", paths.rawTurnsPartial.exists())
        root.deleteRecursively()
    }

    @Test
    fun `an atomic replacement never leaves a half-written file`() {
        val root = Files.createTempDirectory("v6-atomic").toFile()
        val target = File(root, "status.json")
        DeviceRunEvidenceV6.replaceAtomically(target, """{"a":1}""")
        assertEquals("""{"a":1}""", target.readText(Charsets.UTF_8))
        DeviceRunEvidenceV6.replaceAtomically(target, """{"a":2}""")
        assertEquals("""{"a":2}""", target.readText(Charsets.UTF_8))
        assertEquals("no temporary may survive", emptyList<String>(),
            root.listFiles { f -> f.name.endsWith(DeviceRunEvidenceV6.PARTIAL_SUFFIX) }
                .orEmpty().map { it.name })
        root.deleteRecursively()
    }

    @Test
    fun `the v6 raw schema declares every field the host readers need`() {
        assertEquals("ryeong_v6_raw_turn/v1", DeviceRunEvidenceV6.RAW_TURN_SCHEMA)
        assertEquals(
            "the four slot states must be the ones the metric contract declares",
            listOf("MISSING", "NULL", "EMPTY", "POPULATED"), DeviceRunEvidenceV6.SLOT_STATES,
        )
        listOf(
            "expected_slots_state",
            "scenario_opened_with_selected_contact",
            "scenario_opened_with_history",
        ).forEach {
            assertTrue("$it must be a declared v6 addition",
                it in DeviceRunEvidenceV6.ADDED_RAW_FIELDS)
        }
    }
}
