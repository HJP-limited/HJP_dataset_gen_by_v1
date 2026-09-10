package com.example.hjp.v7char

import com.example.hjp.v7char.V7CharacterizationFixture.K3_RESULT
import com.example.hjp.v7char.V7CharacterizationFixture.K6_EXISTING_COMPARISON
import com.example.hjp.v7char.V7CharacterizationFixture.K6_HOST_VALIDITY
import com.example.hjp.v7char.V7CharacterizationFixture.K6_RESULT
import com.example.hjp.v7char.V7CharacterizationFixture.COMPARISON_CONTRACT
import com.example.hjp.v7char.V7CharacterizationFixture.at
import com.example.hjp.v7char.V7CharacterizationFixture.intOf
import com.example.hjp.v7char.V7CharacterizationFixture.requireObject
import com.example.hjp.v7char.V7CharacterizationFixture.stringOf
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defect A, as a test.
 *
 * `compare_k3_k4_k5_k6.py` reads `result["route_accuracy_strict"]["numerator"]`. RUN_K3, K4 and K5
 * put it there. RUN_K6 does not: its record carries a metric block per reader, because v6's whole
 * change was that four readers each compute every metric. So the comparison read `None` ten times
 * and wrote `identical_across_runs: false`.
 *
 * That `false` is the part that matters. It is not "K6 measured something different" — it is "K6 was
 * not read", and a comparison that renders the two the same way cannot be used to decide anything.
 *
 * What this test pins:
 *
 *  * the defect is real and reproducible at the root of the real K6 record;
 *  * the values *are* there, one level down, and they are the ones the four readers agreed on;
 *  * a v7 contract exists that says where to look, per schema, rather than guessing;
 *  * the v6 comparison artefact is left exactly as v6 wrote it, wrong K6 column and all.
 */
class V7NestedResultSchemaCharacterizationTest {

    /** The ten metrics the v6 comparison listed. Its K6 column is null for every one of them. */
    private val v6ComparisonMetrics = listOf(
        "route_accuracy_strict", "route_accuracy_store_retrieval", "hit_at_5", "jga",
        "focus_accuracy", "follow_up_resolution", "session_reset_correctness",
        "no_card_suppression", "keyword_coverage", "semantic_coverage",
    )

    @Test
    fun `the K6 record does not carry its metrics where the v6 comparison looks`() {
        val k6 = requireObject(K6_RESULT)
        assertEquals("ryeong_v6_official_result/v1", stringOf(k6["schema"]))
        v6ComparisonMetrics.forEach { metric ->
            assertNull(
                "$metric is at the root of the K6 record after all; the defect is not what was described",
                k6[metric],
            )
        }
    }

    @Test
    fun `the K3 record does carry them there, which is why one lookup was ever enough`() {
        val k3 = requireObject(K3_RESULT)
        assertEquals("ryeong_v3_official_result/v1", stringOf(k3["schema"]))
        v6ComparisonMetrics.forEach { metric ->
            val entry = k3[metric] as? JsonObject
            assertNotNull("$metric is missing from the K3 record", entry)
            assertNotNull("$metric has no numerator in K3", intOf(entry!!["numerator"]))
        }
    }

    @Test
    fun `one level down, K6 holds the values the four readers agreed on`() {
        val k6 = requireObject(K6_RESULT)
        val validity = requireObject(K6_HOST_VALIDITY)
        assertEquals("VALID BASELINE", stringOf(validity["validity_verdict"]))

        val nested = at(k6, "$.kotlin_official_evaluator.metrics") as? JsonObject
        assertNotNull("the K6 record has no nested metric block", nested)

        val table = validity["metric_table"] as JsonObject
        assertEquals(
            "the authority publishes fifteen metrics",
            15,
            table.size,
        )
        table.forEach { (metric, agreement) ->
            val agreed = agreement as JsonObject
            assertEquals("$metric: the four readers did not agree in the v6 authority",
                "true", agreed["agree"].toString())
            val authority = agreed["kotlin_official_evaluator"] as JsonObject
            val recorded = nested!![metric] as? JsonObject
            assertNotNull("$metric is absent from the nested block", recorded)
            assertEquals(
                "$metric numerator disagrees with the four-reader authority",
                intOf(authority["numerator"]), intOf(recorded!!["numerator"]),
            )
            assertEquals(
                "$metric denominator disagrees with the four-reader authority",
                intOf(authority["denominator"]), intOf(recorded["denominator"]),
            )
        }
    }

    @Test
    fun `the v6 comparison artefact still records the defect and is not repaired in place`() {
        val existing = requireObject(K6_EXISTING_COMPARISON)
        val table = existing["metric_table"] as JsonObject
        v6ComparisonMetrics.forEach { metric ->
            val row = table[metric] as JsonObject
            val k6 = row["K6"] as JsonObject
            assertEquals("$metric: the v6 comparison's K6 numerator is no longer null",
                "null", k6["numerator"].toString())
            assertEquals("$metric: the v6 comparison's K6 denominator is no longer null",
                "null", k6["denominator"].toString())
            assertEquals(
                "$metric: the v6 comparison no longer records identical_across_runs false",
                "false", row["identical_across_runs"].toString(),
            )
        }
    }

    @Test
    fun `a v7 contract says where each schema keeps its metrics`() {
        val contract = requireObject(COMPARISON_CONTRACT)
        val schemas = contract["record_schemas"] as? JsonObject
        assertNotNull("the v7 comparison contract registers no record schemas", schemas)

        val v6Schema = schemas!!["ryeong_v6_official_result/v1"] as? JsonObject
        assertNotNull("the v7 contract does not register the K6 record schema", v6Schema)
        assertEquals(
            "the v7 contract does not point at the nested K6 metric block",
            "$.kotlin_official_evaluator.metrics",
            stringOf(v6Schema!!["metric_source_path"]),
        )

        val v3Schema = schemas["ryeong_v3_official_result/v1"] as? JsonObject
        assertNotNull("the v7 contract does not register the K3 record schema", v3Schema)
        assertEquals(
            "the v7 contract does not read K3 at the root",
            "$", stringOf(v3Schema!!["metric_source_path"]),
        )

        assertTrue(
            "the v7 contract must separate an unread schema from a differing value",
            V7CharacterizationFixture.strings(contract["failure_codes"]).containsAll(
                listOf("VALUE_MISMATCH", "UNREADABLE_SCHEMA", "METRIC_PATH_MISSING",
                    "REQUIRED_METRIC_MISSING", "REQUIRED_CENSUS_MISSING",
                    "REQUIRED_COUNTER_MISSING", "PARSE_ERROR", "UNSUPPORTED_SCHEMA"),
            ),
        )
    }

    @Test
    fun `the v7 contract keeps K4 invalid and K5 valid under the gate that judged them`() {
        val contract = requireObject(COMPARISON_CONTRACT)
        val runs = contract["runs"] as? JsonObject
        assertNotNull("the v7 comparison contract declares no runs", runs)

        val k4 = runs!!["K4"] as JsonObject
        assertEquals("INVALID RUN", stringOf(k4["validity"]))
        val k5 = runs["K5"] as JsonObject
        assertEquals("VALID BASELINE", stringOf(k5["validity"]))
        assertTrue(
            "the contract must say which gate judged K5, because that gate did not compare JGA",
            stringOf(k5["validity_authority"]).orEmpty().contains("v5"),
        )
        val k6 = runs["K6"] as JsonObject
        assertEquals("VALID BASELINE", stringOf(k6["validity"]))
        assertTrue(
            "K6's authority is the four-reader host validity, not the earlier comparison report",
            stringOf(k6["validity_authority"]).orEmpty().contains("host_validity.json"),
        )
        assertTrue(
            "the contract must refuse the already-written comparison report as an authority",
            V7CharacterizationFixture.strings(contract["forbidden_authorities"])
                .any { it.contains("K3_K4_K5_K6_COMPARISON.json") },
        )
    }
}
