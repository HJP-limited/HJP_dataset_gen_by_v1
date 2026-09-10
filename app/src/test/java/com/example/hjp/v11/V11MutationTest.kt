package com.example.hjp.v11

import com.example.hjp.v11.V11Contracts.at
import com.example.hjp.v11.V11Contracts.bool
import com.example.hjp.v11.V11Contracts.int
import com.example.hjp.v11.V11Contracts.list
import com.example.hjp.v11.V11Contracts.registryRuns
import com.example.hjp.v11.V11Contracts.requireObj
import com.example.hjp.v11.V11Contracts.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A check that never fails is indistinguishable from no check. */
class V11MutationTest {

    private val report =
        "integration_evidence/evaluation/ryeong_official_v11/pre_run/mutation/MUTATION_REPORT.json"

    private fun mutations(): List<JsonObject> =
        (at(requireObj(report), "mutations") as JsonArray).map { it as JsonObject }

    @Test
    fun `every mutation was detected`() {
        val document = requireObj(report)
        assertEquals("ALL MUTATIONS DETECTED", str(document["verdict"]))
        assertEquals(emptyList<String>(), list(document["undetected"]))
        assertTrue("there must be mutations", (int(document["detected"]) ?: 0) > 0)
    }

    @Test
    fun `all three families are covered`() {
        val families = (at(requireObj(report), "family_counts") as JsonObject).keys
        assertEquals(setOf("observed_state", "lifecycle", "verdict"), families)
        families.forEach { family ->
            assertEquals("$family must be fully detected",
                         int(at(requireObj(report), "family_counts/$family/total")),
                         int(at(requireObj(report), "family_counts/$family/detected")))
        }
    }

    @Test
    fun `each mutation names the code it expected and got that code`() {
        val entries = mutations()
        assertTrue("there must be mutations", entries.isNotEmpty())
        val nameless = entries.filter { str(it["expected_failure_code"]) == null }
            .mapNotNull { str(it["id"]) }
        assertEquals("every mutation must declare the code it expects", emptyList<String>(),
                     nameless)
        val wrongCode = entries.filter {
            val expected = str(it["expected_failure_code"])
            expected != null && !list(it["codes_raised"]).contains(expected)
        }.mapNotNull { str(it["id"]) }
        assertEquals("a mutation caught by the wrong check is not the check working",
                     emptyList<String>(), wrongCode)
    }

    @Test
    fun `v10's own defect is one of the mutations and it is caught`() {
        val target = mutations().firstOrNull {
            (str(it["id"]) ?: "").contains("completed_run_as_not_yet_executed")
        }
        assertNotNull("the defect v11 exists to prevent must be a mutation", target)
        assertEquals(true, bool(target!!["detected"]))
        assertEquals("COMPLETED_RUN_STATE_MISREPORTED",
                     str(target["expected_failure_code"]))
    }

    @Test
    fun `a completed run stripped of its metrics is caught`() {
        val target = mutations().firstOrNull {
            (str(it["id"]) ?: "").contains("completed_run_metrics_missing")
        }
        assertNotNull(target)
        assertEquals("COMPLETED_RUN_METRICS_MISSING", str(target!!["expected_failure_code"]))
        assertEquals(true, bool(target["detected"]))
    }

    @Test
    fun `re-running a stage is caught refusing rather than replacing`() {
        val target = mutations().firstOrNull {
            (str(it["id"]) ?: "").contains("stage_rerun_overwrites")
        }
        assertNotNull("the v10 stage defect must be a mutation", target)
        assertEquals(true, bool(target!!["detected"]))
        assertTrue("the refusal must have been recorded somewhere other than the target",
                   list(target["codes_raised"]).contains("STAGE_REFUSAL_RECORDED_ELSEWHERE"))
    }

    @Test
    fun `weakening any verdict policy is caught`() {
        val ids = mutations().mapNotNull { str(it["id"]) }
        listOf("emergency_hold_ignored", "failed_gate_ignored", "bundle_alone_grants_ready",
               "blocking_code_with_no_gate", "denominator_not_derived",
               "comparison_axes_reduced").forEach { shape ->
            assertTrue("a mutation must weaken $shape; ids are $ids",
                       ids.any { it.contains(shape) })
        }
    }

    @Test
    fun `the mutations were applied to copies and the tree is unchanged`() {
        assertEquals(true, bool(at(requireObj(report), "restoration/all_restored")))
        assertEquals(0, int(at(requireObj(report), "restoration/still_modified")))
        assertEquals(true, bool(at(requireObj(report), "restoration/mutations_applied_to_copies")))
    }

    @Test
    fun `the fixtures are marked non-official and reach no evidence file`() {
        assertEquals("NON_OFFICIAL_MUTATION_FIXTURE",
                     str(at(requireObj(report), "fixture_provenance/status")))
        assertEquals(true, bool(at(requireObj(report),
                                   "fixture_provenance/no_number_reaches_official_evidence")))
    }
}
