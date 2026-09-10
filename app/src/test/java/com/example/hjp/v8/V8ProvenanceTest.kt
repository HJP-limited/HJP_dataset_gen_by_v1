package com.example.hjp.v8

import com.example.hjp.v8.V8Contracts.CARDINALITY_PARITY
import com.example.hjp.v8.V8Contracts.CONTRACT_RETARGET
import com.example.hjp.v8.V8Contracts.DEVICE_RUNNER_RETARGET
import com.example.hjp.v8.V8Contracts.ENVIRONMENT_PROVENANCE
import com.example.hjp.v8.V8Contracts.HOST_RUNNER_RETARGET
import com.example.hjp.v8.V8Contracts.PATH_POLICY_RETARGET
import com.example.hjp.v8.V8Contracts.PRODUCTION_ROOTS
import com.example.hjp.v8.V8Contracts.READER_PROVENANCE
import com.example.hjp.v8.V8Contracts.TEMPLATE_RETARGET
import com.example.hjp.v8.V8Contracts.V7_FAILING_FIXTURE
import com.example.hjp.v8.V8Contracts.V7_FAILING_SOURCE
import com.example.hjp.v8.V8Contracts.WITNESS
import com.example.hjp.v8.V8Contracts.bool
import com.example.hjp.v8.V8Contracts.file
import com.example.hjp.v8.V8Contracts.int
import com.example.hjp.v8.V8Contracts.list
import com.example.hjp.v8.V8Contracts.obj
import com.example.hjp.v8.V8Contracts.requireObj
import com.example.hjp.v8.V8Contracts.sha256
import com.example.hjp.v8.V8Contracts.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where v8's code came from, and what it promised not to touch.
 *
 * Three claims run through the whole version, and each is easy to make and easy to get wrong:
 *
 *   1. the readers are reused, not rewritten — byte-identical copies, proved rather than asserted;
 *   2. the retargeted files are reproducible from their frozen sources by a declared substitution;
 *   3. production is unchanged, and so is the frozen v7 suite that fails on purpose.
 *
 * The third is the one that matters most. v8 exists because a frozen expectation was wrong, and the
 * single most tempting thing to do about that is to edit it. The digests below are what make "we
 * did not" checkable.
 */
class V8ProvenanceTest {

    // ==============================================================================================
    // the frozen v7 suite is untouched, and still says what it said
    // ==============================================================================================

    @Test
    fun `the frozen v7 characterization source and fixture are exactly as the witness found them`() {
        val witness = obj(WITNESS)
        assertNotNull(
            "the v7 historical failure witness is not in the tree; run " +
                "tools/ryeong_official_v8/witness_v7_historical_failure_v8.py: $WITNESS",
            witness,
        )
        assertEquals("the frozen v7 test source has changed since the witness was taken",
            str(witness!!["source_sha256"]), sha256(V7_FAILING_SOURCE))
        assertEquals("the frozen v7 fixture has changed since the witness was taken",
            str(witness["fixture_sha256"]), sha256(V7_FAILING_FIXTURE))
        assertEquals("the frozen v7 case table has changed since the witness was taken",
            str(witness["case_table_sha256"]), sha256(str(witness["case_table_path"])!!))
        assertEquals("the frozen v6 template has changed since the witness was taken",
            str(witness["v6_template_sha256"]), sha256(str(witness["v6_template_path"])!!))
        assertEquals("reading the frozen inputs changed them",
            true, bool(witness["frozen_inputs_unchanged_by_this_read"]))
    }

    @Test
    fun `the v7 expectation still reads three, and the real answer is still five`() {
        val witness = requireObj(WITNESS)
        assertEquals("the frozen expectation is no longer 3",
            3, int(witness["incorrect_frozen_operational_occurrence_expectation"]))
        assertEquals("the observed occurrence count is no longer 5",
            5, int(witness["observed_operational_occurrence_count"]))
        assertEquals("the distinct target count is no longer 3",
            3, int(witness["observed_distinct_invalid_target_count"]))
        assertEquals("the expected distinct target count is no longer 3",
            3, int(witness["expected_distinct_invalid_target_count"]))
        assertEquals("python and kotlin no longer agree on the pointer set",
            true, bool(witness["python_kotlin_pointer_parity"]))
        assertEquals("the witness is no longer complete",
            "HISTORICAL FAILURE WITNESSED", str(witness["verdict"]))
    }

    @Test
    fun `RUN_K7 is still absent`() {
        val witness = requireObj(WITNESS)
        assertEquals("RUN_K7 has acquired an invocation", 0, int(witness["run_k7_invocation_count"]))
        assertEquals("a RUN_K7 invocation marker has appeared",
            false, bool(witness["run_k7_marker_present"]))
        assertEquals("the v7 output namespace is no longer empty",
            emptyList<String>(), list(witness["run_k7_output_namespace_entries"]))
        assertEquals("HOLD BEFORE V7 DEVICE PREFLIGHT", str(witness["v7_final_verdict"]))

        assertTrue(
            "a RUN_K7 status file has appeared",
            !file("integration_evidence/evaluation/ryeong_official_v7/result/jvm_keyword/" +
                "run_status.json").exists(),
        )
        assertTrue(
            "a RUN_K7 invocation marker has appeared",
            !file("integration_evidence/evaluation/ryeong_official_v7/execution/jvm_keyword/" +
                "invocation.marker").exists(),
        )
    }

    // ==============================================================================================
    // the readers are copies, and the retargets are reproducible
    // ==============================================================================================

    @Test
    fun `the frozen readers were copied byte for byte`() {
        val provenance = obj(READER_PROVENANCE)
        assertNotNull("the reader provenance record is not in the tree: $READER_PROVENANCE",
            provenance)
        assertEquals("READERS COPIED BYTE IDENTICAL", str(provenance!!["verdict"]))

        // Two populations, judged differently on purpose. The six readers must be byte-identical
        // forever, because a character changed in any of them changes a measurement. The twelve
        // procedures are copied and then retargeted, because they carry v7 paths in their own
        // defaults; a difference there is the point. Comparing `copied` against `byte_identical`
        // would demand that the retargets never happened.
        assertEquals("a reader differs from the frozen source it was copied from",
            int(provenance["readers_checked"]), int(provenance["byte_identical"]))
        assertEquals("the six readers are the ones that must never be edited",
            6, int(provenance["readers_checked"]))
        assertEquals("every copy must be accounted for as a reader or a procedure",
            int(provenance["copied"]),
            (int(provenance["readers_checked"]) ?: 0) +
                list(provenance["procedures_copied_then_retargeted"]).size)
        list(provenance["never_edited_readers"]).forEach { reader ->
            assertTrue("$reader is declared never-edited and is not in the tree",
                V8Contracts.present(reader))
        }

        (provenance["copies"] as JsonArray).map { it.jsonObject }.forEach { row ->
            if (bool(row["target_present"]) != true) return@forEach
            val target = str(row["target"])!!
            // The retargeted procedures are expected to differ from their sources by now; the
            // readers are not, and that distinction is the whole point of the two lists.
            val retargeted = list(provenance["some_are_retargeted_afterwards"])
            if (retargeted.contains(target)) return@forEach
            assertEquals(
                "$target is a reader and is no longer byte-identical to the frozen source it was " +
                    "copied from; editing one would change a measurement",
                str(row["source_sha256"]), sha256(target),
            )
        }
    }

    @Test
    fun `every retarget is reproducible from its frozen source`() {
        listOf(
            HOST_RUNNER_RETARGET to "RETARGET REPRODUCIBLE",
            DEVICE_RUNNER_RETARGET to "RETARGET REPRODUCIBLE",
            PATH_POLICY_RETARGET to "RETARGET REPRODUCIBLE",
            TEMPLATE_RETARGET to "TEMPLATE RETARGET REPRODUCIBLE",
            CONTRACT_RETARGET to "CONTRACT RETARGET REPRODUCIBLE",
        ).forEach { (relative, expected) ->
            val record = obj(relative)
            assertNotNull("the retarget record is not in the tree: $relative", record)
            assertEquals("$relative did not reproduce", expected, str(record!!["verdict"]))
        }
    }

    @Test
    fun `the K8 runner derives from the frozen K7 runner and inverts exactly one check`() {
        val record = requireObj(HOST_RUNNER_RETARGET)
        assertEquals("the K8 runner was not derived from the frozen v7 runner",
            "app/src/test/java/com/example/hjp/eval/v7/RyeongV7OfficialRunTest.kt",
            str(record["source"]))
        assertEquals("the v7 runner source has changed",
            str(record["source_sha256"]), sha256(str(record["source"])!!))
        assertEquals("the generated K8 runner does not match the declared derivation",
            true, bool(record["reproducible_from_source"]))

        val inverted = record["the_one_inverted_check"] as? JsonObject
        assertNotNull(
            "the retarget does not record the one check that had to be inverted. Every earlier run " +
                "is asserted unchanged; RUN_K7 must be asserted ABSENT, and a substitution cannot " +
                "express that",
            inverted,
        )
        assertTrue("the inverted check is not the K7 absence assertion",
            str(inverted!!["what"])?.contains("assertRunAbsent") == true)

        val runner = file("app/src/test/java/com/example/hjp/eval/v8/RyeongV8OfficialRunTest.kt")
        assertTrue("the K8 runner is not in the tree", runner.isFile)
        val text = runner.readText(Charsets.UTF_8)
        assertTrue("the K8 runner does not refuse to start if a K7 result appeared",
            text.contains("assertRunAbsent(K7_STATUS, K7_MARKER)"))
        assertTrue("the K8 runner does not carry the v8 run id",
            text.contains("RYEONG_PRODUCTION_COMPATIBILITY_V8_RUN_K8_JVM_KEYWORD_HOST_BASELINE"))
        assertTrue("the K8 runner still checks the earlier runs are where they were left",
            text.contains("assertRunUnchanged(K6_STATUS"))
        assertTrue("the raw schema moved; v8 changed nothing that measures",
            !text.contains("ryeong_v8_raw_turn"))
    }

    @Test
    fun `the two readers agree and the agreement is on record`() {
        val parity = obj(CARDINALITY_PARITY)
        assertNotNull("the cardinality parity report is not in the tree: $CARDINALITY_PARITY",
            parity)
        assertEquals("CARDINALITY PARITY", str(parity!!["verdict"]))
        assertEquals("the two readers disagree: " + list(parity["disagreements"]),
            emptyList<String>(), list(parity["disagreements"]))
        assertEquals("the parity check must compare all three templates",
            3, list(parity["templates_compared"]).size)
        listOf("operational_occurrence_count", "distinct_invalid_target_count",
            "operational_occurrence_pointers", "distinct_invalid_targets",
            "pointer_to_target").forEach { field ->
            assertTrue("the parity check does not compare $field",
                list(parity["compared"]).contains(field))
        }
    }

    // ==============================================================================================
    // production is unchanged
    // ==============================================================================================

    @Test
    fun `v8 added no production source`() {
        val v8Marks = listOf("V8Cardinality", "ryeong_official_v8", "RUN_K8", "RUN_D8",
            "ryeong_device_eval_v8")
        val offenders = mutableListOf<String>()
        PRODUCTION_ROOTS.forEach { root ->
            val directory = file(root)
            if (!directory.isDirectory) return@forEach
            directory.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { source ->
                    val text = source.readText(Charsets.UTF_8)
                    v8Marks.forEach { mark ->
                        if (text.contains(mark)) {
                            offenders += "${source.relativeTo(file("")).path} names $mark"
                        }
                    }
                }
        }
        assertEquals(
            "v8 is an evaluation version and changes no production source. A production file that " +
                "names a v8 identifier is either a change v8 said it did not make, or a coupling " +
                "between the app and its own evaluation harness: $offenders",
            emptyList<String>(), offenders,
        )
    }

    // ==============================================================================================
    // the version provenance is recorded as five separate things
    // ==============================================================================================

    @Test
    fun `the toolchain versions are recorded separately, not merged`() {
        val provenance = obj(ENVIRONMENT_PROVENANCE)
        assertNotNull("the environment provenance record is not in the tree", provenance)

        listOf("gradle_distribution", "gradle_embedded_kotlin", "kotlin_gradle_plugin",
            "kotlin_compiler_actual", "android_gradle_plugin", "jdk").forEach { field ->
            assertTrue("the provenance record does not carry $field",
                provenance!!.containsKey(field))
        }
        val embedded = provenance!!["gradle_embedded_kotlin"] as JsonObject
        val plugin = provenance["kotlin_gradle_plugin"] as JsonObject
        assertNotNull("the Gradle embedded Kotlin was never captured", str(embedded["value"]))
        assertNotNull("the Kotlin Gradle plugin version was never captured", str(plugin["value"]))
        assertTrue(
            "the two Kotlin versions are recorded as equal. They are different numbers about " +
                "different compilers — v6 reported one and v7 the other — and collapsing them is " +
                "the reporting ambiguity this record exists to end",
            str(embedded["value"]) != str(plugin["value"]),
        )
        assertTrue("the record does not say what the embedded Kotlin governs",
            str(embedded["governs"])?.contains("gradle.kts") == true)
        assertTrue("the record does not say what the plugin version governs",
            str(plugin["governs"])?.contains("src/main") == true)
        assertTrue("the record does not forbid collapsing them",
            str(provenance["collapsing_prohibition"])?.contains("must not be merged") == true)
        assertTrue("there is no v8 field that merges them",
            !provenance.containsKey("kotlin_version"))
    }
}
