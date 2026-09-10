package com.example.hjp.v6

import com.example.hjp.deviceeval.DeviceRunEvidence
import com.example.hjp.deviceeval.DeviceRunEvidenceV5
import com.example.hjp.deviceeval.DeviceRunEvidenceV6
import com.example.hjp.eval.ryeong2.EvidenceRoot
import com.example.hjp.eval.v6.RyeongV6OfficialRunTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three runs, three identifiers, three output namespaces, and no way for one to be mistaken for
 * another.
 *
 * `RUN_K6` is a host baseline, `RUN_D6` is the device run of record, and the smoke run is neither.
 * The failure this guards against is not exotic: a smoke run that writes where the official run
 * writes produces a short file wearing the official identifier, and no later reader can tell. So the
 * separation is asserted here — on the host, where it is cheap — rather than discovered on a device
 * after the one invocation has been spent.
 */
class V6RunnerContractTest {

    @Test
    fun `the three runs have three identifiers and none contains another`() {
        val k6 = RyeongV6OfficialRunTest.RUN_ID
        val d6 = DeviceRunEvidenceV6.RunMode.OFFICIAL.runId
        val smoke = DeviceRunEvidenceV6.RunMode.SMOKE.runId

        assertEquals("RYEONG_PRODUCTION_COMPATIBILITY_V6_RUN_K6_JVM_KEYWORD_HOST_BASELINE", k6)
        assertEquals("RYEONG_PRODUCTION_COMPATIBILITY_V6_RUN_D6_DEVICE_ACTUAL_MODEL_BASELINE", d6)
        assertEquals("RYEONG_PRODUCTION_COMPATIBILITY_V6_SMOKE_DEVICE", smoke)

        val ids = listOf(k6, d6, smoke)
        assertEquals("the three identifiers must be distinct", 3, ids.toSet().size)
        ids.forEach { left ->
            ids.filter { it != left }.forEach { right ->
                assertFalse("'$left' must not contain '$right'", left.contains(right))
            }
        }
    }

    @Test
    fun `the three runs write to three namespaces and none is a prefix of another`() {
        val namespaces = listOf(
            RyeongV6OfficialRunTest.DEFAULT_OUT,
            DeviceRunEvidenceV6.RunMode.OFFICIAL.directoryName,
            DeviceRunEvidenceV6.RunMode.SMOKE.directoryName,
        )
        assertEquals("ryeong_official_v6/result/jvm_keyword",
            namespaces[0].substringAfter("integration_evidence/evaluation/"))
        assertEquals(3, namespaces.toSet().size)
        namespaces.forEach { left ->
            namespaces.filter { it != left }.forEach { right ->
                assertFalse("'$left' must not start with '$right'", left.startsWith("$right/"))
            }
        }
    }

    @Test
    fun `the host run refuses every earlier evaluation namespace`() {
        listOf(
            "ryeong_official_v1", "ryeong_official_v2", "ryeong_official_v3",
            "ryeong_official_v4", "ryeong_official_v5",
        ).forEach {
            assertTrue("$it must be forbidden to RUN_K6",
                it in RyeongV6OfficialRunTest.FORBIDDEN_NAMESPACES)
        }
        assertTrue("RUN_K6 must write into the v6 namespace",
            RyeongV6OfficialRunTest.DEFAULT_OUT.contains("ryeong_official_v6"))
        RyeongV6OfficialRunTest.FORBIDDEN_NAMESPACES.forEach {
            assertFalse("the v6 output path must not mention $it",
                RyeongV6OfficialRunTest.DEFAULT_OUT.contains(it))
        }
    }

    @Test
    fun `no earlier device runner can be reached through the v6 mode enum`() {
        val v6Directories = DeviceRunEvidenceV6.RunMode.entries.map { it.directoryName }.toSet()
        val earlier = DeviceRunEvidenceV5.RunMode.entries.map { it.directoryName } +
            DeviceRunEvidence.RunMode.entries.map { it.directoryName }
        earlier.forEach {
            assertFalse("a v6 mode must not resolve to $it", it in v6Directories)
            assertTrue("$it must be foreign to v6", it in DeviceRunEvidenceV6.FOREIGN_DIRECTORIES)
        }
    }

    @Test
    fun `the v6 device runner classes exist and the earlier ones are untouched`() {
        listOf(
            "app/src/androidTest/java/com/example/hjp/RyeongDeviceRunnerV6.kt",
            "app/src/androidTest/java/com/example/hjp/RyeongDeviceEvaluationV6Test.kt",
            "app/src/androidTest/java/com/example/hjp/RyeongDeviceSmokeV6Test.kt",
            "device-evidence/src/main/kotlin/com/example/hjp/deviceeval/DeviceRunEvidenceV6.kt",
        ).forEach {
            assertTrue("$it must exist", EvidenceRoot.file(it).isFile)
        }
        // The earlier runners stay on disk as history and must not have been repurposed.
        listOf(
            "app/src/androidTest/java/com/example/hjp/RyeongDeviceRunnerV4.kt",
            "app/src/androidTest/java/com/example/hjp/RyeongDeviceRunnerV5.kt",
        ).forEach { path ->
            val text = EvidenceRoot.file(path).readText(Charsets.UTF_8)
            assertFalse("$path must not reference v6", text.contains("DeviceRunEvidenceV6"))
            assertFalse("$path must not reference RUN_D6", text.contains("RUN_D6"))
        }
    }

    @Test
    fun `the official host run declares the sources the freeze has to pin`() {
        val pinned = RyeongV6OfficialRunTest.PINNED_SOURCES
        listOf(
            "app/src/test/java/com/example/hjp/eval/v6/RyeongV6Scorer.kt",
            "app/src/test/java/com/example/hjp/eval/v6/RyeongV6Recomputation.kt",
            "app/src/test/java/com/example/hjp/eval/v6/V6Slots.kt",
            "tools/ryeong_official_v6/contracts/metric_scoring_contract.json",
            "tools/ryeong_official_v6/score_run_v6.py",
            "tools/ryeong_official_v6/recompute_run_v6.py",
            "tools/ryeong_official_v6/compare_readers_v6.py",
            "tools/ryeong_official_v6/validate_run_v6.py",
        ).forEach {
            assertTrue("the run must refuse to start unless $it is pinned", it in pinned)
        }
        pinned.forEach {
            assertTrue("a pinned source that does not exist cannot be verified: $it",
                EvidenceRoot.file(it).isFile)
        }
    }

    @Test
    fun `the official host run is opt-in and its output list covers every artefact it writes`() {
        listOf(
            "result.json", "raw_turns.jsonl", "gate_verdict.json", "run_status.json",
            "kotlin_official_evaluator_report.json", "kotlin_recomputation_report.json",
            "kotlin_reader_comparison.json",
        ).forEach {
            assertTrue("$it must be refused if it already exists",
                it in RyeongV6OfficialRunTest.OUTPUT_FILES)
        }
        val source = EvidenceRoot.file(
            "app/src/test/java/com/example/hjp/eval/v6/RyeongV6OfficialRunTest.kt",
        ).readText(Charsets.UTF_8)
        assertTrue("the run must be opt-in", source.contains("RYEONG_V6_OFFICIAL_RUN"))
        assertTrue("the run must skip rather than execute when not requested",
            source.contains("assumeTrue"))
        assertTrue("the raw record must be promoted by rename", source.contains("renameTo(finalRaw)"))
        assertTrue("the raw record must be synced per turn", source.contains("stream.fd.sync()"))
        assertTrue("the invocation marker must be written before the run",
            source.indexOf("invocation.marker") < source.indexOf("runner.run(scenarios.scenarios)"))
    }
}
