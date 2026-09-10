package com.example.hjp.v7

import com.example.hjp.v7.V7Contracts.obj
import com.example.hjp.v7.V7Contracts.sha256
import com.example.hjp.v7.V7Contracts.strings
import com.example.hjp.v7.V7Contracts.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7 changed the cross-run comparison and the Phase B materials. It changed nothing that measures,
 * and this is where that stops being a sentence.
 *
 * Two dependency styles, each for a stated reason:
 *
 *  * the four Python readers, the metric table and the contact-dependency rule are **byte-identical
 *    copies** at v7 paths, because a v7 Phase B command may not name a previous version's tool —
 *    that is the defect this version exists to close, and pointing the command sheet at
 *    `tools/ryeong_official_v6/score_run_v6.py` would be the same mistake in the other direction;
 *  * the two Kotlin readers are the frozen v6 classes, used **unchanged and digest-pinned**, because
 *    they are classes on a test classpath rather than paths in an operator's procedure.
 *
 * Both are checked here rather than asserted in prose.
 */
class V7ProvenanceTest {

    private val provenance =
        "integration_evidence/evaluation/ryeong_official_v7/provenance/READER_PROVENANCE.json"

    private val copies = listOf(
        "tools/ryeong_official_v6/score_run_v6.py"
            to "tools/ryeong_official_v7/score_run_v7.py",
        "tools/ryeong_official_v6/recompute_run_v6.py"
            to "tools/ryeong_official_v7/recompute_run_v7.py",
        "tools/ryeong_official_v6/compare_readers_v6.py"
            to "tools/ryeong_official_v7/compare_readers_v7.py",
        "tools/ryeong_official_v6/validate_run_v6.py"
            to "tools/ryeong_official_v7/validate_run_v7.py",
        "tools/ryeong_official_v6/contracts/metric_scoring_contract.json"
            to "tools/ryeong_official_v7/contracts/metric_scoring_contract.json",
        "tools/ryeong_official_v5/contracts/contact_dependency_contract.json"
            to "tools/ryeong_official_v7/contracts/contact_dependency_contract.json",
    )

    private val pinnedKotlinReaders = listOf(
        "RyeongV6Scorer", "RyeongV6Recomputation", "RyeongV6Gate", "V6Comparison", "V6Contract",
        "V6Slots", "V6Dataset", "V6Observation", "V6Metrics", "V6ReportJson",
    ).map { "app/src/test/java/com/example/hjp/eval/v6/$it.kt" }

    @Test
    fun `each v7 reader is byte-identical to the frozen file it was copied from`() {
        copies.forEach { (source, copy) ->
            assertTrue("the source is missing: $source", V7Contracts.file(source).isFile)
            assertTrue("the copy is missing: $copy", V7Contracts.file(copy).isFile)
            assertEquals("$copy is not byte-identical to $source", sha256(source), sha256(copy))
        }
    }

    @Test
    fun `the provenance record says the same thing this test just checked`() {
        val record = obj(provenance)
        assertEquals("true", record["all_byte_identical"].toString())
        val declared = (record["copies"] as JsonArray).map { it as JsonObject }
        assertEquals("the record covers a different number of files",
            copies.size, declared.size)
        declared.forEach { entry ->
            val source = text(entry["source"])!!
            val copy = text(entry["copy"])!!
            assertEquals("$copy", sha256(source), text(entry["source_sha256"]))
            assertEquals("$copy", sha256(copy), text(entry["copy_sha256"]))
        }
        assertTrue(
            "the record must say why a copy was made rather than a reference taken",
            text(record["choice_reason"]).orEmpty().contains("Phase B"),
        )
    }

    @Test
    fun `the frozen Kotlin readers are the ones the provenance record pins`() {
        val record = obj(provenance)
        val pinned = strings((record["kotlin_readers"] as JsonObject)["pinned"])
        pinnedKotlinReaders.forEach { path ->
            assertTrue("$path is not pinned as a v7 dependency", pinned.contains(path))
            assertTrue("$path is not in the tree", V7Contracts.file(path).isFile)
        }
    }

    @Test
    fun `the device runner and the host runner are reproducible from their v6 sources`() {
        listOf(
            "integration_evidence/evaluation/ryeong_official_v7/provenance/DEVICE_RUNNER_RETARGET.json",
            "integration_evidence/evaluation/ryeong_official_v7/provenance/HOST_RUNNER_RETARGET.json",
        ).forEach { path ->
            val record = obj(path)
            assertEquals("$path is not a reproducible retarget",
                "RETARGET REPRODUCIBLE", text(record["verdict"]))
        }
    }

    @Test
    fun `the raw schema stayed v6 on purpose, and the record says why`() {
        val device = obj(
            "integration_evidence/evaluation/ryeong_official_v7/provenance/DEVICE_RUNNER_RETARGET.json")
        val notSubstituted = device["deliberately_not_substituted"] as JsonObject
        assertTrue(
            "the record must explain why ryeong_v6_raw_turn/v1 was left alone",
            notSubstituted.keys.any { it.contains("ryeong_v6_raw_turn") },
        )
        val contract = obj(V7Contracts.CROSS_RUN)
        val k7 = (contract["runs"] as JsonObject)["K7"] as JsonObject
        assertEquals("ryeong_v6_raw_turn/v1", text(k7["expected_raw_schema"]))
        assertTrue(
            "the contract must say why K7 records a v6 raw schema",
            text(k7["raw_schema_note"]).orEmpty().contains("changed nothing that measures"),
        )
    }

    @Test
    fun `the Phase B template and command sheet are both in the tree`() {
        assertTrue(V7Contracts.file(V7Contracts.TEMPLATE).isFile)
        assertTrue(V7Contracts.file(V7Contracts.COMMANDS).isFile)
        val commands = V7Contracts.file(V7Contracts.COMMANDS).readText(Charsets.UTF_8)
        listOf("pm clear", "uninstall").forEach { destructive ->
            assertTrue(
                "$destructive must be named as excluded, not as a step",
                commands.contains("Not part of this procedure"),
            )
        }
        assertTrue(
            "the command sheet must run the v7 official device runner",
            commands.contains("com.example.hjp.RyeongDeviceEvaluationV7Test"),
        )
        assertTrue(
            "the command sheet must run the v7 smoke runner",
            commands.contains("com.example.hjp.RyeongDeviceSmokeV7Test"),
        )
    }
}
