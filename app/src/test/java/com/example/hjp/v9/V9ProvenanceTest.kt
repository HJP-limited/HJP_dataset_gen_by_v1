package com.example.hjp.v9

import com.example.hjp.v9.V9Contracts.APFS_AUDIT
import com.example.hjp.v9.V9Contracts.CONTRACT_GENERATION
import com.example.hjp.v9.V9Contracts.DIGEST_RECONCILIATION
import com.example.hjp.v9.V9Contracts.DRIFT_WITNESS
import com.example.hjp.v9.V9Contracts.ENV_PROVENANCE
import com.example.hjp.v9.V9Contracts.K8_DIAGNOSTIC
import com.example.hjp.v9.V9Contracts.K8_HOST_VALIDITY
import com.example.hjp.v9.V9Contracts.K8_RESULT
import com.example.hjp.v9.V9Contracts.K8_STATUS
import com.example.hjp.v9.V9Contracts.KOTLIN_GENERATION
import com.example.hjp.v9.V9Contracts.PATH_COLLISION
import com.example.hjp.v9.V9Contracts.PRODUCTION_ROOTS
import com.example.hjp.v9.V9Contracts.READER_PROVENANCE
import com.example.hjp.v9.V9Contracts.REGISTRY_BUILD
import com.example.hjp.v9.V9Contracts.V7_FAILING_SOURCE
import com.example.hjp.v9.V9Contracts.V8_BLOCKER
import com.example.hjp.v9.V9Contracts.V8_CROSS_RUN
import com.example.hjp.v9.V9Contracts.V8_FREEZE_DRIFT
import com.example.hjp.v9.V9Contracts.V8_VERDICT
import com.example.hjp.v9.V9Contracts.ZIP_SCOPE
import com.example.hjp.v9.V9Contracts.at
import com.example.hjp.v9.V9Contracts.bool
import com.example.hjp.v9.V9Contracts.file
import com.example.hjp.v9.V9Contracts.int
import com.example.hjp.v9.V9Contracts.list
import com.example.hjp.v9.V9Contracts.obj
import com.example.hjp.v9.V9Contracts.requireObj
import com.example.hjp.v9.V9Contracts.sha256
import com.example.hjp.v9.V9Contracts.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where v9's code came from, and what it promised not to touch.
 *
 * The claim v9 rests on is that it changed how a run is *read* and changed nothing about any run.
 * The digests below are what make that checkable rather than asserted — in particular for K8, whose
 * record v9 reads correctly and must not otherwise disturb.
 */
class V9ProvenanceTest {

    @Test
    fun `the frozen v8 drift record is exactly as the witness found it`() {
        val witness = obj(DRIFT_WITNESS)
        assertNotNull("the v8 schema drift witness is not in the tree: $DRIFT_WITNESS", witness)
        assertEquals("V8 SCHEMA DRIFT WITNESSED", str(witness!!["verdict"]))
        assertEquals(true, bool(witness["frozen_inputs_unchanged_by_this_read"]))
        assertEquals("ryeong_v8_official_result/v1", str(witness["actual_k8_result_schema"]))
        assertEquals("ryeong_v7_official_result/v1",
            str(witness["contract_expected_k8_result_schema"]))
        assertEquals(false, bool(witness["schemas_match"]))
        assertEquals(false, bool(witness["actual_schema_registered_in_contract"]))
        assertEquals(true, bool(witness["runner_retarget_declares_the_schema_move"]))
        assertEquals(false, bool(witness["contract_retarget_declares_the_schema_move"]))
        assertEquals("V8_CROSS_RUN_CONTRACT_SCHEMA_DRIFT", str(witness["finding_code"]))
        assertEquals("RUNNER_AND_CONTRACT_RETARGET_LISTS_DIVERGED", str(witness["reason_code"]))
    }

    @Test
    fun `K8's record, status and validity are untouched`() {
        val witness = requireObj(DRIFT_WITNESS)
        val before = witness["frozen_inputs_sha256_before"] as JsonObject
        assertEquals("K8's result record has changed since the witness was taken",
            str(before["k8_result"]), sha256(K8_RESULT))
        assertEquals("K8's status has changed", str(before["k8_status"]), sha256(K8_STATUS))
        assertEquals("K8's host validity has changed",
            str(before["k8_host_validity"]), sha256(K8_HOST_VALIDITY))
        assertEquals("the frozen v8 cross-run contract has changed",
            str(before["cross_run_contract"]), sha256(V8_CROSS_RUN))
        assertEquals("the v8 blocker report has changed",
            str(before["v8_blocker"]), sha256(V8_BLOCKER))

        val verdict = requireObj(V8_VERDICT)
        assertEquals("v8's version-level verdict has been rewritten",
            "HOLD BEFORE V8 DEVICE PREFLIGHT", str(verdict["FINAL_VERDICT"]))
        assertEquals("v8's record of K8's run-local validity has been rewritten",
            "VALID BASELINE", str(at(verdict, "run_k8/final_host_validity")))
    }

    @Test
    fun `K8 reads correctly under v9 and its verdicts are unchanged by the read`() {
        val diagnostic = obj(K8_DIAGNOSTIC)
        assertNotNull("the K8 read-only diagnostic is not in the tree", diagnostic)
        assertEquals(listOf("NOT_OFFICIAL_V8_REJUDGMENT", "READ_ONLY_V9_DIAGNOSTIC"),
            list(diagnostic!!["status"]))
        assertEquals(true, bool(diagnostic["readable"]))
        assertEquals(true, bool(diagnostic["comparable"]))
        assertEquals(true, bool(diagnostic["all_three_schemas_agree"]))
        assertEquals(emptyList<String>(), list(diagnostic["failure_codes"]))
        assertEquals(15, int(diagnostic["metrics_compared"]))
        assertEquals("a metric read under v9 disagrees with the v8 four-reader authority",
            0, int(diagnostic["metric_disagreement_count"]))
        assertEquals(1452, int(at(diagnostic, "four_reader_authority/fields_compared")))
        assertEquals(0, int(at(diagnostic, "four_reader_authority/disagreement_count")))
        assertEquals("VALID BASELINE", str(diagnostic["k8_run_local_validity"]))
        assertEquals("HOLD BEFORE V8 DEVICE PREFLIGHT", str(diagnostic["v8_version_level_verdict"]))
        assertEquals("reading K8 must not change K8", true, bool(diagnostic["source_unchanged"]))
    }

    @Test
    fun `the frozen readers were copied byte for byte`() {
        val provenance = obj(READER_PROVENANCE)
        assertNotNull("the reader provenance record is not in the tree", provenance)
        assertEquals("READERS COPIED BYTE IDENTICAL", str(provenance!!["verdict"]))
        assertEquals(true, bool(provenance["readers_byte_identical"]))
        assertEquals(6, int(provenance["readers_checked"]))
        (provenance["copies"] as JsonArray).map { it.jsonObject }.forEach { row ->
            if (str(row["kind"]) != "reader") return@forEach
            assertEquals(
                "${str(row["target"])} is a reader and is no longer byte-identical to its frozen " +
                    "source; editing one would change a measurement",
                str(row["source_sha256"]), sha256(str(row["target"])!!),
            )
        }
    }

    @Test
    fun `the registry, the contract and the constants were all generated and reproduce`() {
        listOf(REGISTRY_BUILD to "REGISTRY BUILT",
            CONTRACT_GENERATION to "CONTRACT GENERATED",
            KOTLIN_GENERATION to "KOTLIN IDENTITY GENERATED").forEach { (path, expected) ->
            val record = obj(path)
            assertNotNull("the generation record is not in the tree: $path", record)
            assertEquals("$path did not reproduce", expected, str(record!!["verdict"]))
        }
        assertEquals(true, bool(requireObj(CONTRACT_GENERATION)["reproducible_from_registry"]))
        assertEquals(true, bool(requireObj(KOTLIN_GENERATION)["reproducible_from_registry"]))
        assertEquals(true, bool(requireObj(KOTLIN_GENERATION)["all_copies_identical"]))
    }

    @Test
    fun `the baseline reconciliations are resolved`() {
        val digest = obj(DIGEST_RECONCILIATION)
        assertNotNull("the digest reconciliation is not in the tree", digest)
        assertTrue("the v7 bundle digest disagreement is unresolved",
            str(digest!!["verdict"]) != "UNRESOLVED_DIGEST_DISAGREEMENT")
        assertEquals("the file and v7's own verification must agree",
            true, bool(digest["procedure_artifacts_agree_with_file"]))
        assertEquals(64, int(digest["actual_file_digest_length"]))
        assertEquals("no characters may be added or removed to make digests match",
            true, bool(digest["no_characters_were_added_or_removed"]))

        val zips = obj(ZIP_SCOPE)
        assertNotNull("the ZIP scope reconciliation is not in the tree", zips)
        assertEquals("ZIP SCOPE RECONCILED", str(zips!!["verdict"]))
        assertEquals(false, bool(zips["same_scope_contradiction"]))

        val apfs = obj(APFS_AUDIT)
        assertNotNull("the APFS collision audit is not in the tree", apfs)
        assertEquals("COLLISION AUDIT RESOLVED", str(apfs!!["verdict"]))
        assertEquals("no authority artefact may have been a collision target",
            false, bool(apfs["raw_result_status_marker_were_collision_targets"]))
        assertEquals(true, bool(apfs["deterministically_reproducible"]))
        assertEquals(true, bool(apfs["authority_artifacts_present"]))

        val collisions = obj(PATH_COLLISION)
        assertNotNull("the path collision audit is not in the tree", collisions)
        assertEquals(0, int(collisions!!["casefold_collision_count"]))
        assertEquals(0, int(collisions["unicode_normalization_collision_count"]))
    }

    @Test
    fun `the v8 freeze drift finding is recorded and no authority artefact moved`() {
        val finding = obj(V8_FREEZE_DRIFT)
        assertNotNull(
            "v9's re-verification of the v8 freeze found one file changed after v8's own " +
                "post-freeze. That finding must be on the record: $V8_FREEZE_DRIFT",
            finding,
        )
        assertEquals("V8_FROZEN_TOOL_EDITED_AFTER_POST_FREEZE", str(finding!!["code"]))
        assertEquals("no v8 authority artefact may have moved",
            0, int(finding["authority_artifacts_moved_count"]))
        assertEquals(false, bool(finding["blocks_v9"]))
        assertTrue("the finding must forbid repairing v8 to make its freeze verify",
            list(finding["what_v9_must_not_do"]).any { it.contains("restore or edit") })
    }

    @Test
    fun `the frozen v7 failing characterization is untouched`() {
        assertTrue("the frozen v7 characterization source is not in the tree",
            file(V7_FAILING_SOURCE).isFile)
        val contract = requireObj(V9Contracts.HISTORICAL_FAILURE)
        val conditions = ((contract["expected_historical_failures"] as JsonArray)[0]
            .jsonObject["match_conditions"]) as JsonObject
        assertEquals(
            "the frozen v7 test source has changed; the historical failure would no longer be the " +
                "one that was frozen",
            str(conditions["source_sha256"]), sha256(V7_FAILING_SOURCE),
        )
    }

    @Test
    fun `v9 added no production source`() {
        val marks = listOf("V9RunIdentity", "ryeong_official_v9", "RUN_K9", "RUN_D9",
            "ryeong_device_eval_v9")
        val offenders = mutableListOf<String>()
        PRODUCTION_ROOTS.forEach { root ->
            val directory = file(root)
            if (!directory.isDirectory) return@forEach
            directory.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { source ->
                    val body = source.readText(Charsets.UTF_8)
                    marks.forEach { mark ->
                        if (body.contains(mark)) {
                            offenders += "${source.relativeTo(file("")).path} names $mark"
                        }
                    }
                }
        }
        assertEquals(
            "v9 is an evaluation version and changes no production source: $offenders",
            emptyList<String>(), offenders,
        )
    }

    @Test
    fun `the toolchain versions are recorded separately and the JDK gap is closed`() {
        val provenance = obj(ENV_PROVENANCE)
        assertNotNull("the environment provenance record is not in the tree", provenance)
        val embedded = provenance!!["gradle_embedded_kotlin"] as JsonObject
        val plugin = provenance["kotlin_gradle_plugin"] as JsonObject
        assertNotNull(str(embedded["value"]))
        assertNotNull(str(plugin["value"]))
        assertTrue(
            "the two Kotlin versions are recorded as equal. They are different numbers about " +
                "different compilers, and collapsing them is the ambiguity v8 ended",
            str(embedded["value"]) != str(plugin["value"]),
        )
        assertTrue("there is no field that merges them",
            !provenance.containsKey("kotlin_version"))
        assertEquals(
            "v8 recorded an empty JDK release block because it looked in two places and the " +
                "Homebrew layout puts the file in a third. The fields were absent, not wrong",
            true, bool(at(provenance, "jdk/release_fields_present")),
        )
        assertNotNull(str(at(provenance, "jdk/release_fields/JAVA_VERSION")))
    }
}
