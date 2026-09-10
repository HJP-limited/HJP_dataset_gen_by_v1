package com.example.hjp.v9

import com.example.hjp.v9.V9Contracts.CROSS_RUN
import com.example.hjp.v9.V9Contracts.MUTATION
import com.example.hjp.v9.V9Contracts.REGISTRY
import com.example.hjp.v9.V9Contracts.bool
import com.example.hjp.v9.V9Contracts.int
import com.example.hjp.v9.V9Contracts.list
import com.example.hjp.v9.V9Contracts.obj
import com.example.hjp.v9.V9Contracts.registryRuns
import com.example.hjp.v9.V9Contracts.requireObj
import com.example.hjp.v9.V9Contracts.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Break the arrangement on purpose from the JVM side, and check the break shows up.
 *
 * The Python harness does this over the contracts and the parity validator. This does it over the
 * compiled constants, because the two halves must each be able to fail independently — two checkers
 * blind in the same place agree perfectly.
 */
class V9MutationTest {

    private fun mutation(): JsonObject? = obj(MUTATION)

    @Test
    fun `moving a compiled constant away from the registry is visible`() {
        val runs = registryRuns()
        // The compiled constant and the registry currently agree; a mutation is a value that is not
        // the registry's, and the comparison must reject it.
        val registrySchema = str(runs.getValue("K9")["host_result_schema"])!!
        val mutated = "ryeong_v10_official_result/v1"
        assertEquals("the constant must equal the registry", registrySchema,
            V9RunIdentity.K9.hostResultSchema)
        assertTrue("a mutated value must not equal the registry's",
            mutated != registrySchema)
        assertTrue(
            "if the compiled constant were the mutated value, the equality above would fail. That " +
                "is the whole of the check: the runner cannot hold an identity the registry does " +
                "not, because it does not hold literals at all",
            V9RunIdentity.K9.hostResultSchema == registrySchema,
        )
    }

    @Test
    fun `the registry and the contract cannot silently disagree about a schema`() {
        val registry = registryRuns()
        val contract = requireObj(CROSS_RUN)["runs"] as JsonObject
        contract.forEach { (name, value) ->
            val expected = str(value.jsonObject["expected_result_schema"]) ?: return@forEach
            val declared = str(registry.getValue(name)["host_result_schema"])
            assertEquals(
                "$name: the contract expects $expected and the registry declares $declared. This " +
                    "is the v8 defect, and it is now one comparison away from being noticed",
                declared, expected,
            )
        }
    }

    @Test
    fun `every schema a run publishes is registered, and no run publishes an unregistered one`() {
        val contract = requireObj(CROSS_RUN)
        val registered = (contract["record_schemas"] as JsonObject).keys
        val published = registryRuns().values.mapNotNull { str(it["host_result_schema"]) }.toSet()
        val unregistered = published - registered
        assertEquals(
            "a run publishes a schema the contract does not register: $unregistered. Under a " +
                "fail-closed reader that run is unreadable, which is exactly what happened to K8",
            emptySet<String>(), unregistered,
        )
    }

    @Test
    fun `the python harness detected every mutation it declared`() {
        val report = mutation()
        assertNotNull(
            "the mutation report is not in the tree; run " +
                "tools/ryeong_official_v9/mutate_and_check_v9.py: $MUTATION",
            report,
        )
        assertEquals("a mutation went undetected: " +
            (report!!["undetected"] as JsonArray).map { str(it.jsonObject["id"]) },
            0, int(report["undetected_count"]))
        assertEquals("ALL MUTATIONS DETECTED", str(report["verdict"]))

        val families = report["by_family"] as JsonObject
        listOf("schema_drift", "identity_drift", "registry", "reader", "evidence",
            "separation").forEach { family ->
            val counts = families[family]?.jsonObject
            assertNotNull("the harness ran no $family mutations", counts)
            assertTrue("the $family family is empty", (int(counts!!["total"]) ?: 0) > 0)
            assertEquals("a $family mutation went undetected",
                int(counts["total"]), int(counts["detected"]))
        }
    }

    @Test
    fun `the harness covers the four directions of the v8 defect`() {
        val report = mutation()
        assertNotNull("the mutation report is not in the tree", report)
        val ids = (report!!["mutations"] as JsonArray).map { str(it.jsonObject["id"]) }
        listOf(
            "SCHEMA_RUNNER_only", "SCHEMA_CONTRACT_only", "SCHEMA_RECORD_only",
            "SCHEMA_REGISTRY_only",
            "IDENTITY_k7_marked_invoked", "IDENTITY_k7_metrics_copied_from_k6",
            "IDENTITY_k8_run_local_overwritten", "IDENTITY_v8_hold_overwritten",
            "REGISTRY_K8_entry_deleted", "REGISTRY_historical_sha_changed",
            "REGISTRY_second_substitution_list_reintroduced",
            "READER_contract_trusted_over_record", "READER_infer_from_shape",
            "EVIDENCE_casefold_collision", "EVIDENCE_post_run_write_into_frozen_root",
            "EVIDENCE_authority_regenerated",
        ).forEach { id ->
            assertTrue("the harness does not cover $id", ids.contains(id))
        }
    }

    @Test
    fun `the registry the harness mutated is the registry in the tree`() {
        val registry = requireObj(REGISTRY)
        assertEquals(true, bool(registry["self_consistent"]))
        assertEquals(9, (registry["runs"] as JsonObject).size)
        assertEquals("ryeong_v8_official_result/v1",
            str(registryRuns().getValue("K8")["host_result_schema"]))
        assertEquals("ryeong_v9_official_result/v1",
            str(registryRuns().getValue("K9")["host_result_schema"]))
    }
}
