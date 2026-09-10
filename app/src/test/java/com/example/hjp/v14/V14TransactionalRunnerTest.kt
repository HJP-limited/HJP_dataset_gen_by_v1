package com.example.hjp.v14

import com.example.hjp.eval.v14.V14Admission
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unit checks whose result the v14 durable attestation records.
 *
 * The ordering test is the one that matters most. v13 validated the final preflight before the
 * CHECK_ONLY early return, and that single misplaced line cost a whole version: CHECK_ONLY could
 * not pass without a document only a passing CHECK_ONLY could produce.
 */
class V14TransactionalRunnerTest {

    @Test fun `generated identity is the canonical K14 view`() {
        assertEquals("K14", V14RunIdentity.K14.RUN_KEY)
        assertTrue(V14RunIdentity.CANONICAL_FIELDS.contains("rerun_policy"))
        assertTrue(V14RunIdentity.CANONICAL_FIELDS.contains("metric_source_path"))
        assertFalse(V14RunIdentity.CANONICAL_FIELDS.contains("host_output_namespace"))
        assertEquals(64, V14RunIdentity.REGISTRY_DIGEST.length)
        assertEquals(
            listOf("K3", "K4", "K5", "K6", "K7", "K8", "K9", "K10", "K11", "K12", "K13", "K14",
                "D14", "V14_SMOKE"),
            V14RunIdentity.RUN_ORDER,
        )
    }

    @Test fun `historically closed runs are closed by policy, not by marker absence`() {
        assertEquals("closed_historical_hold", V14RunIdentity.K7.RERUN_POLICY)
        assertEquals("closed_historical_hold", V14RunIdentity.K12.RERUN_POLICY)
        assertEquals("closed_historical_hold", V14RunIdentity.K13.RERUN_POLICY)
        assertEquals("closed_partial", V14RunIdentity.K11.RERUN_POLICY)
        assertEquals("current_pre_run", V14RunIdentity.K14.RERUN_POLICY)
        assertEquals("NOT_EXECUTED_DUE_TO_V13_PREFLIGHT_HOLD", V14RunIdentity.K13.HISTORICAL_TERMINAL_STATE)
    }

    @Test fun `each run carries its own metric pointer`() {
        assertEquals("$", V14RunIdentity.K3.METRIC_SOURCE_PATH)
        assertEquals("$.kotlin_official_evaluator.metrics", V14RunIdentity.K10.METRIC_SOURCE_PATH)
        assertEquals(10, V14RunIdentity.K3.METRIC_COUNT_WHEN_COMPLETED)
        assertEquals(15, V14RunIdentity.K10.METRIC_COUNT_WHEN_COMPLETED)
    }

    @Test fun `device and smoke runs are separated from the host run`() {
        assertEquals("host", V14RunIdentity.K14.RUN_KIND)
        assertEquals("device_official", V14RunIdentity.D14.RUN_KIND)
        assertEquals("device_smoke", V14RunIdentity.V14_SMOKE.RUN_KIND)
        val namespaces = listOf(V14RunIdentity.K14.OUTPUT_NAMESPACE,
            V14RunIdentity.D14.OUTPUT_NAMESPACE, V14RunIdentity.V14_SMOKE.OUTPUT_NAMESPACE)
        assertEquals(namespaces.size, namespaces.toSet().size)
    }

    @Test fun `CHECK_ONLY returns before any final admission validation`() {
        val source = source()
        val prerequisites = source.indexOf("validateCommonPrerequisites()")
        val checkOnlyGuard = source.indexOf("if (checkOnly) {")
        val admission = source.indexOf("validateFinalAdmission()")
        assertTrue(listOf(prerequisites, checkOnlyGuard, admission).all { it >= 0 })
        assertTrue("prerequisites must be validated first", prerequisites < checkOnlyGuard)
        assertTrue("the final admission must come after the CHECK_ONLY return",
            checkOnlyGuard < admission)
    }

    @Test fun `the runner never decides admission by substring search`() {
        val source = source()
        assertFalse(source.contains("readText(Charsets.UTF_8).contains("))
        assertFalse(source.contains("contains(needle)"))
        assertTrue(source.contains("V14Admission.validatePrerequisites("))
        assertTrue(source.contains("V14Admission.validateAdmission("))
    }

    @Test fun `runner orders claim directory partial and finalization`() {
        val source = source()
        val claim = source.indexOf("StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE")
        val directory = source.indexOf("Files.createDirectories(dir.toPath())")
        val partial = source.indexOf("Files.newOutputStream(partial.toPath(), StandardOpenOption.CREATE_NEW")
        val raw = source.indexOf("Files.createLink(finalRaw.toPath(), partial.toPath())")
        val result = source.indexOf("transition(stateTrace, \"RESULT_FINALIZED\")")
        val status = source.indexOf("transition(stateTrace, \"STATUS_FINALIZED\")")
        val remove = source.indexOf("partial.delete()")
        assertTrue(listOf(claim, directory, partial, raw, result, status, remove).all { it >= 0 })
        assertTrue(claim < directory && directory < partial && partial < raw &&
            raw < result && result < status && status < remove)
    }

    @Test fun `earlier runs including K12 and K13 are asserted absent or unchanged`() {
        val source = source()
        assertTrue(source.contains("assertRunAbsent(K7_STATUS, K7_MARKER)"))
        assertTrue(source.contains("assertRunAbsent(K12_STATUS, K12_MARKER)"))
        assertTrue(source.contains("assertRunAbsent(K13_STATUS, K13_MARKER)"))
        assertTrue(source.contains("assertK11PartialUnchanged()"))
        assertTrue(source.contains("6deb7ab47a9502771378340afcf3c3789708840ce534c375278c78c895a41560"))
    }

    @Test fun `the approved quarantine path is asserted absent`() {
        val source = source()
        assertTrue(source.contains("QUARANTINE_EXACT_PATH"))
        assertTrue(source.contains("integration_evidence/evaluation/ryeong_official_v10/.DS_Store"))
        assertFalse(source.contains("*.DS_Store"))
        assertFalse(source.contains("endsWith(\".DS_Store\")"))
    }

    @Test fun `create new and partial retention work on absent namespace`() {
        val root = Files.createTempDirectory("v14-kotlin-lifecycle-")
        val marker = root.resolve("execution/invocation.marker")
        val dir = root.resolve("result")
        assertFalse(dir.toFile().exists())
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "one", StandardOpenOption.CREATE_NEW)
        Files.createDirectories(dir)
        val partial = dir.resolve("raw_turns.jsonl.partial")
        Files.writeString(partial, "raw\n", StandardOpenOption.CREATE_NEW)
        val raw = dir.resolve("raw_turns.jsonl")
        Files.createLink(raw, partial)
        assertEquals("raw\n", raw.readText())
        Files.delete(partial)
        var refused = false
        try {
            Files.writeString(marker, "two", StandardOpenOption.CREATE_NEW)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            refused = true
        }
        assertTrue(refused)
    }

    // ---- structured admission validation -------------------------------------------------------

    private fun expected() = V14Admission.Expected(
        schema = "ryeong_v14_run_admission/v1", stage = "final_admission", version = 14,
        runKey = "K14", runId = "RUN_ID_X", verdict = "RUN_K14 ADMISSION PASS",
    )

    private fun goodAdmission(): String = """
        {"schema":"ryeong_v14_run_admission/v1","stage":"final_admission","version":14,
         "run_key":"K14","run_id":"RUN_ID_X","verdict":"RUN_K14 ADMISSION PASS",
         "prerequisites_sha256":"a","check_only_sha256":"b","gate_contract_sha256":"c",
         "registry_sha256":"d","final_freeze_sha256":"e","protected_report_sha256":"f",
         "actual_tree_digest":"g","required_gate_count":5,"passed_gate_count":5,
         "failed_gates":[],"blocking_findings":[],"run_k14_allowed":true,
         "check_only_exit_code":0,"check_only_read_only_behavior_pass":true,
         "check_only_runner_prerequisite_validation_pass":true}
    """.trimIndent()

    @Test fun `a well formed admission is accepted`() {
        assertEquals(emptyList<V14Admission.Failure>(),
            V14Admission.validateAdmission(goodAdmission(), expected()))
    }

    @Test fun `a verdict string in a decoy field does not admit the run`() {
        val decoy = """{"schema":"ryeong_v14_run_admission/v1","stage":"candidate","version":14,
            "run_key":"K14","run_id":"RUN_ID_X","verdict":"RUN_K14 ADMISSION HOLD",
            "description":"verdict: RUN_K14 ADMISSION PASS",
            "nested":{"run_k14_allowed":true,"verdict":"RUN_K14 ADMISSION PASS"},
            "prerequisites_sha256":"a","check_only_sha256":"b","gate_contract_sha256":"c",
            "registry_sha256":"d","final_freeze_sha256":"e","protected_report_sha256":"f",
            "actual_tree_digest":"g","required_gate_count":5,"passed_gate_count":5,
            "failed_gates":[],"blocking_findings":[],"run_k14_allowed":false,
            "check_only_exit_code":0,"check_only_read_only_behavior_pass":true,
            "check_only_runner_prerequisite_validation_pass":true}""".trimIndent()
        val failures = V14Admission.validateAdmission(decoy, expected())
        assertTrue("a decoy must be refused", failures.isNotEmpty())
        assertTrue(failures.any { it.code == "STAGE_MISMATCH" })
        assertTrue(failures.any { it.code == "RUN_NOT_ALLOWED" })
    }

    @Test fun `a string true is not a boolean true`() {
        val text = goodAdmission().replace("\"run_k14_allowed\":true", "\"run_k14_allowed\":\"true\"")
        val failures = V14Admission.validateAdmission(text, expected())
        assertTrue(failures.any { it.code == "FIELD_TYPE" && it.detail.contains("run_k14_allowed") })
    }

    @Test fun `a quoted number is not a number`() {
        val text = goodAdmission().replace("\"required_gate_count\":5", "\"required_gate_count\":\"5\"")
        val failures = V14Admission.validateAdmission(text, expected())
        assertTrue(failures.any { it.code == "FIELD_TYPE" && it.detail.contains("required_gate_count") })
    }

    @Test fun `a duplicate key is detected before parsing collapses it`() {
        val text = goodAdmission().replace("\"run_k14_allowed\":true",
            "\"run_k14_allowed\":false,\"run_k14_allowed\":true")
        assertTrue(V14Admission.duplicateKeys(text).contains("run_k14_allowed"))
        val failures = V14Admission.validateAdmission(text, expected())
        assertTrue(failures.any { it.code == "PARSE" })
    }

    @Test fun `unparseable text is refused`() {
        val failures = V14Admission.validateAdmission("{ not json", expected())
        assertEquals(1, failures.size)
        assertEquals("PARSE", failures.first().code)
    }

    @Test fun `a missing required field is refused rather than defaulted`() {
        val text = goodAdmission().replace("\"failed_gates\":[],", "")
        val failures = V14Admission.validateAdmission(text, expected())
        assertTrue(failures.any { it.code == "FIELD_MISSING" && it.detail == "failed_gates" })
    }

    @Test fun `a v13 artifact is refused as a v14 admission`() {
        val text = goodAdmission()
            .replace("ryeong_v14_run_admission/v1", "ryeong_v13_preflight/v1")
            .replace("\"version\":14", "\"version\":13")
            .replace("\"run_key\":\"K14\"", "\"run_key\":\"K13\"")
        val failures = V14Admission.validateAdmission(text, expected())
        assertTrue(failures.any { it.code == "SCHEMA_MISMATCH" })
        assertTrue(failures.any { it.code == "VERSION_MISMATCH" })
        assertTrue(failures.any { it.code == "RUN_KEY_MISMATCH" })
    }

    private fun source() = java.io.File(
        requireNotNull(generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "settings.gradle.kts").isFile }),
        "app/src/test/java/com/example/hjp/eval/v14/RyeongV14OfficialRunTest.kt",
    ).readText()
}
