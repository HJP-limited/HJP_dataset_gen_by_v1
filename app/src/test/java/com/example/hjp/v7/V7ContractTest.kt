package com.example.hjp.v7

import com.example.hjp.v7.V7Contracts.OFFICIAL_METRICS
import com.example.hjp.v7.V7Contracts.complaints
import com.example.hjp.v7.V7Contracts.obj
import com.example.hjp.v7.V7Contracts.strings
import com.example.hjp.v7.V7Contracts.text
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v7 contracts, checked against rules that are not read from them.
 *
 * A contract is a promise about what will be compared. The way that promise fails is not by being
 * violated — it is by quietly shrinking, which is what happened to JGA in v5: the comparison table
 * simply did not list it, so two readers disagreed about it and the gate reported agreement.
 *
 * So the fifteen metric names and the structural comparison targets are written down in
 * [V7Contracts], not read from the contract under test. A checker that takes its expectations from
 * the thing it is checking cannot notice the thing shrinking.
 */
class V7ContractTest {

    private fun all(): List<String> = complaints(
        obj(V7Contracts.CROSS_RUN), obj(V7Contracts.ACCEPTANCE),
        obj(V7Contracts.SEPARATION), obj(V7Contracts.PATH_POLICY),
    )

    @Test
    fun `the v7 contracts satisfy every rule`() {
        assertEquals("the v7 contracts do not satisfy the checker", emptyList<String>(), all())
    }

    @Test
    fun `the comparison contract knows where each schema keeps its metrics`() {
        val contract = obj(V7Contracts.CROSS_RUN)
        val schemas = contract["record_schemas"] as JsonObject
        assertEquals(
            "K3, K4 and K5 keep their metrics at the root",
            listOf("\$", "\$", "\$"),
            listOf("ryeong_v3_official_result/v1", "ryeong_v4_official_result/v1",
                "ryeong_v5_official_result/v1").map {
                text((schemas[it] as JsonObject)["metric_source_path"])
            },
        )
        assertEquals(
            "K6 and K7 give each reader its own metric block",
            listOf("\$.kotlin_official_evaluator.metrics", "\$.kotlin_official_evaluator.metrics"),
            listOf("ryeong_v6_official_result/v1", "ryeong_v7_official_result/v1").map {
                text((schemas[it] as JsonObject)["metric_source_path"])
            },
        )
    }

    @Test
    fun `unreadable and mismatch are declared as different things`() {
        val contract = obj(V7Contracts.CROSS_RUN)
        val separations = contract["separations"] as JsonObject
        assertNotNull(separations["unreadable_is_not_a_mismatch"])
        assertTrue(
            "the contract must say identical is null, not false, when a side was not read",
            text(separations["unreadable_is_not_a_mismatch"]).orEmpty().contains("null"),
        )
        assertNotNull(separations["schema_capability_is_not_disagreement"])
        assertNotNull(separations["validity_is_not_metric_equality"])
    }

    @Test
    fun `a ratio is compared by its integer terms and a mean by its rendered sum`() {
        val policy = obj(V7Contracts.CROSS_RUN)["metric_field_policy"] as JsonObject
        assertEquals(listOf("numerator", "denominator"), strings(policy["ratio"]))
        assertEquals(listOf("count", "sum_rendered"), strings(policy["mean"]))
        assertTrue(
            "the contract must refuse to compare a rendered quotient",
            text(policy["ratio_comparison"]).orEmpty().contains("never the rendered quotient"),
        )
        assertTrue(
            "no metric may be decided by a floating-point comparison",
            text(policy["floating_point_policy"]).orEmpty().contains("no metric"),
        )
    }

    @Test
    fun `every official metric carries the meaning of its denominator`() {
        val official = obj(V7Contracts.CROSS_RUN)["official_metrics"] as JsonObject
        OFFICIAL_METRICS.forEach { metric ->
            val entry = official[metric] as JsonObject
            val means = text(entry["denominator_means"]).orEmpty()
            assertTrue("$metric has no denominator meaning", means.length > 20)
        }
    }

    @Test
    fun `the acceptance contract authorises exactly the safety gates and nothing else`() {
        val acceptance = obj(V7Contracts.ACCEPTANCE)
        val layerTwo = acceptance["layer_2_performance_acceptance"] as JsonObject
        val notApplicable = layerTwo["pinned_but_not_applicable"] as JsonObject
        val gate = notApplicable["production_gate"] as JsonObject
        assertTrue(
            "the published production gate must be recorded with its source",
            text(gate["authority"]).orEmpty().contains("docs/"),
        )
        assertTrue(
            "and with the reason it does not apply to this axis",
            text(gate["does_not_apply_to"]).orEmpty().contains("RUN_D7"),
        )
        val safety = (layerTwo["safety_hard_gates"] as JsonObject)
        assertTrue(
            "the safety gates must name the zero-tolerance list they come from",
            text(safety["authority"]).orEmpty().contains("zero-tolerance"),
        )
    }

    @Test
    fun `the three runs are separate in identity, namespace and invocation`() {
        val separation = obj(V7Contracts.SEPARATION)
        val runs = separation["runs"] as JsonObject
        assertEquals(
            "RYEONG_PRODUCTION_COMPATIBILITY_V7_RUN_K7_JVM_KEYWORD_HOST_BASELINE",
            text((runs["K7"] as JsonObject)["run_id"]),
        )
        assertEquals(
            "RYEONG_PRODUCTION_COMPATIBILITY_V7_RUN_D7_DEVICE_ACTUAL_MODEL_BASELINE",
            text((runs["D7"] as JsonObject)["run_id"]),
        )
        assertEquals(
            "RYEONG_PRODUCTION_COMPATIBILITY_V7_SMOKE_DEVICE",
            text((runs["SMOKE"] as JsonObject)["run_id"]),
        )
        assertEquals("ryeong_device_eval_v7_official",
            text((runs["D7"] as JsonObject)["output_namespace"]))
        assertEquals("ryeong_device_eval_v7_smoke",
            text((runs["SMOKE"] as JsonObject)["output_namespace"]))
        listOf("ryeong_device_eval_v6_official", "ryeong_device_eval_v5_official",
            "ryeong_device_eval_v4_official").forEach { namespace ->
            assertTrue("$namespace is not refused by name",
                strings(separation["forbidden_namespaces"]).contains(namespace))
        }
    }

    @Test
    fun `the K7 host run is declared keyword-only, so a zero counter is not a model result`() {
        val runs = obj(V7Contracts.SEPARATION)["runs"] as JsonObject
        val k7 = runs["K7"] as JsonObject
        assertEquals("false", k7["measures_actual_model"].toString())
        assertEquals("false", k7["measures_semantic"].toString())
        assertTrue(
            "the contract must say what a zero counter on this run means",
            text(k7["note"]).orEmpty().contains("not a result about a model"),
        )
    }
}
