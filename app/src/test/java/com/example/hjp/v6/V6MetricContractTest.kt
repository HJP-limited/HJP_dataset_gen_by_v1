package com.example.hjp.v6

import com.example.hjp.eval.ryeong2.EvidenceRoot
import com.example.hjp.eval.v6.RyeongV6Gate
import com.example.hjp.eval.v6.V6Contract
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The metric table, pinned three times, so dropping a metric takes three edits and fails on the
 * first.
 *
 * v5 had no metric table at all. Its Kotlin evaluator computed fifteen things, its Kotlin
 * recomputation four, its Python scorer five, and its comparison sixteen field pairs — and each of
 * those numbers was a private property of one file. Nothing could see that JGA appeared in exactly
 * two of the four, and in one of them with a different denominator.
 *
 * So v6 states it in three places that must agree:
 *
 *  1. `tools/ryeong_official_v6/contracts/metric_scoring_contract.json`, which the Python readers
 *     read;
 *  2. [V6Contract.TABLE], the Kotlin restatement, checked against it by [V6Contract.drift];
 *  3. [RyeongV6Gate.REQUIRED_METRICS] and `validate_run_v6.REQUIRED_METRICS`, which are what the
 *     gate and the validator refuse to run without.
 */
class V6MetricContractTest {

    @Test
    fun `the kotlin restatement does not drift from the declared contract`() {
        val drift = V6Contract.drift()
        assertEquals(
            "the Kotlin metric table and metric_scoring_contract.json disagree:\n" +
                drift.joinToString("\n"),
            emptyList<String>(), drift,
        )
    }

    @Test
    fun `the gate's required list is the contract's list, in the same order`() {
        assertEquals(
            "the gate must require exactly what the contract declares",
            V6Contract.OFFICIAL_METRICS, RyeongV6Gate.REQUIRED_METRICS,
        )
    }

    @Test
    fun `the python validator's required list is the same list`() {
        val source = EvidenceRoot.file("tools/ryeong_official_v6/validate_run_v6.py")
            .readText(Charsets.UTF_8)
        val block = source.substringAfter("REQUIRED_METRICS = [").substringBefore("]")
        val declared = Regex("\"([a-z0-9_]+)\"").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(
            "validate_run_v6.py requires a different metric list from the contract",
            V6Contract.OFFICIAL_METRICS, declared,
        )
    }

    @Test
    fun `the case file's required list is the same list`() {
        val declared = (V6Contract.cases()["required_official_metrics"] as JsonArray)
            .map { (it as JsonPrimitive).content }
        assertEquals(
            "characterization_cases.json requires a different metric list from the contract",
            V6Contract.OFFICIAL_METRICS, declared,
        )
    }

    @Test
    fun `every metric declares the meaning of its denominator and it is not empty`() {
        val metrics = (V6Contract.document()["metrics"] as JsonArray).map { it.jsonObject }
        assertEquals("the contract must declare fifteen metrics", 15, metrics.size)
        metrics.forEach { metric ->
            val name = (metric["name"] as JsonPrimitive).content
            listOf("numerator_means", "denominator_means", "inclusion", "exclusion",
                "not_applicable", "not_scorable", "failed_to_run", "pass_condition").forEach { key ->
                val value = (metric[key] as? JsonPrimitive)?.content.orEmpty()
                assertTrue("$name.$key must say something", value.length > 10)
            }
        }
    }

    @Test
    fun `the contract declares the four slot states and refuses to fold any two of them`() {
        val states = ((V6Contract.document()["slot_declaration_states"] as JsonObject)["states"]
            as JsonObject)
        assertEquals(
            "the four states must be exactly these",
            listOf("EMPTY", "MISSING", "NULL", "POPULATED"), states.keys.sorted(),
        )
        val missing = states["MISSING"] as JsonObject
        assertEquals("CONTRACT_ERROR", (missing["census_state"] as JsonPrimitive).content)
        assertEquals("DATASET_EXPECTED_SLOTS_KEY_MISSING",
            (missing["validity_finding"] as JsonPrimitive).content)
        val nullState = states["NULL"] as JsonObject
        assertEquals("NOT_APPLICABLE", (nullState["census_state"] as JsonPrimitive).content)
        assertEquals("an explicit null must never enter the JGA denominator",
            false, (nullState["counts_in_jga_denominator"] as JsonPrimitive).content.toBoolean())
        val empty = states["EMPTY"] as JsonObject
        assertEquals("an empty object is a declaration and does enter the denominator",
            true, (empty["counts_in_jga_denominator"] as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `the contract states the float comparison policy and it is not a rounded value`() {
        val policy = V6Contract.document()["float_comparison_policy"] as JsonObject
        val ratios = (policy["ratios"] as JsonPrimitive).content
        assertTrue("ratios must be compared by their integer terms",
            ratios.contains("integer numerator") && ratios.contains("never the comparison key"))
        val means = (policy["means"] as JsonPrimitive).content
        assertTrue("means must be compared as a rendered rounded sum", means.contains("%.9f"))
    }

    @Test
    fun `the contract refuses to let a known limitation excuse a mismatch`() {
        val gate = V6Contract.document()["gate_policy"] as JsonObject
        val text = (gate["known_limitations_may_not_absolve"] as JsonPrimitive).content
        assertTrue("the policy must say a described mismatch is still a mismatch",
            text.contains("still a mismatch"))
        assertEquals(
            "every reader must compute every official metric",
            true,
            (gate["every_official_metric_must_be_computed_by_every_reader"] as JsonPrimitive)
                .content.toBoolean(),
        )
        val readers = (gate["readers"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(
            listOf("kotlin_independent_recomputation", "kotlin_official_evaluator",
                "python_independent_recomputation", "python_scorer"),
            readers.sorted(),
        )
    }

    @Test
    fun `the contract records that the v5 contact rule is carried into v6 unchanged`() {
        val contact = V6Contract.document()["contact_dependency"] as JsonObject
        assertEquals("ryeong-v5-contact-dependency-rule-1",
            (contact["rule_version"] as JsonPrimitive).content)
        assertEquals("v6 must not change the contact rule",
            false, (contact["changed_in_v6"] as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `the contract says which metrics a v5-schema raw record cannot fully answer`() {
        val support = V6Contract.document()["raw_schema_support"] as JsonObject
        val v6 = support["ryeong_v6_raw_turn/v1"] as JsonObject
        assertEquals("a v6 record must answer every metric",
            true, (v6["all_metrics_derivable"] as JsonPrimitive).content.toBoolean())
        val v5 = support["ryeong_v5_raw_turn/v1"] as JsonObject
        assertEquals("a v5 record must not claim to answer every metric",
            false, (v5["all_metrics_derivable"] as JsonPrimitive).content.toBoolean())
        assertEquals(
            "session_reset_correctness is the one a v5 record cannot fully answer",
            listOf("session_reset_correctness"),
            (v5["not_fully_derivable"] as JsonArray).map { (it as JsonPrimitive).content },
        )
    }
}
