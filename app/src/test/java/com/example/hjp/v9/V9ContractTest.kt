package com.example.hjp.v9

import com.example.hjp.v9.V9Contracts.ACCEPTANCE
import com.example.hjp.v9.V9Contracts.CASE_TABLE
import com.example.hjp.v9.V9Contracts.CROSS_RUN
import com.example.hjp.v9.V9Contracts.EVIDENCE_PATHS
import com.example.hjp.v9.V9Contracts.FREEZE_POLICY
import com.example.hjp.v9.V9Contracts.HISTORICAL_FAILURE
import com.example.hjp.v9.V9Contracts.PATH_POLICY
import com.example.hjp.v9.V9Contracts.REGISTRY
import com.example.hjp.v9.V9Contracts.RUN_SEPARATION
import com.example.hjp.v9.V9Contracts.at
import com.example.hjp.v9.V9Contracts.bool
import com.example.hjp.v9.V9Contracts.int
import com.example.hjp.v9.V9Contracts.list
import com.example.hjp.v9.V9Contracts.registryRuns
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
 * The v9 contracts, read as documents rather than as intentions.
 *
 * Each of these files is a promise about how v9 behaves, and a promise nothing checks is a comment.
 */
class V9ContractTest {

    @Test
    fun `the registry declares itself the authority and every required field per run`() {
        val registry = requireObj(REGISTRY)
        assertTrue("the registry does not declare itself the authority",
            str(registry["authority"])?.contains("single source") == true)
        val required = list(registry["required_fields_per_run"])
        assertTrue("the required-field list is empty", required.isNotEmpty())
        listOf("host_run_id", "host_result_schema", "raw_turn_schema", "device_result_schema",
            "host_output_namespace", "device_official_namespace", "device_smoke_namespace",
            "host_invocation_marker", "device_invocation_marker", "smoke_invocation_marker",
            "metric_source_path", "census_source_path", "runtime_counter_source_path",
            "runtime_mode").forEach { field ->
            assertTrue("the registry does not require $field", required.contains(field))
        }
        assertEquals(true, bool(registry["self_consistent"]))
        assertEquals(0, int(at(registry, "self_consistency/duplicate_identity_value_count")))
        assertEquals(0, int(at(registry, "self_consistency/run_id_containment_count")))
    }

    @Test
    fun `every historical run's identity was derived from its own record`() {
        val runs = registryRuns()
        listOf("K3", "K4", "K5", "K6", "K8").forEach { name ->
            val entry = runs.getValue(name)
            assertEquals(
                "$name produced a record, so its identity must be derived from it rather than " +
                    "transcribed from a contract that describes it — which is how v8's contract " +
                    "came to describe a record that did not exist",
                true, bool(entry["derived_from_record"]),
            )
            assertTrue("$name has no pinned historical result",
                !str(entry["historical_result_path"]).isNullOrBlank())
            assertEquals("$name's pinned digest is stale",
                sha256(str(entry["historical_result_path"])!!),
                str(entry["historical_result_sha"]))
        }
        listOf("K7", "K9", "D9", "V9_SMOKE").forEach { name ->
            assertEquals("$name has not produced a record and must not claim to have",
                false, bool(runs.getValue(name)["derived_from_record"]))
        }
    }

    @Test
    fun `the raw schema states distinguish absent from disagreeing`() {
        val registry = requireObj(REGISTRY)
        val states = registry["raw_schema_states"] as? JsonObject
        assertNotNull("the registry declares no raw-schema states", states)
        listOf("DERIVED_FROM_RECORD", "RECORD_DECLARES_NO_SCHEMA", "NO_RAW_RECORD").forEach { s ->
            assertTrue("the registry does not declare the state $s", states!!.containsKey(s))
        }
        val k3 = registryRuns().getValue("K3")
        assertEquals(
            "K3's raw record states no schema of its own. Adopting the label a later contract gave " +
                "it would be inventing a fact",
            "RECORD_DECLARES_NO_SCHEMA", str(k3["raw_turn_schema_source"]),
        )
        assertEquals(false, bool(k3["raw_turn_schema_declared_by_record"]))
        assertTrue("K3 must declare no raw schema", str(k3["raw_turn_schema"]).isNullOrBlank())

        val k8 = registryRuns().getValue("K8")
        assertEquals("DERIVED_FROM_RECORD", str(k8["raw_turn_schema_source"]))
        assertEquals("ryeong_v6_raw_turn/v1", str(k8["raw_turn_schema"]))
    }

    @Test
    fun `the cross-run contract is generated from the registry and says so`() {
        val contract = requireObj(CROSS_RUN)
        assertEquals("the contract does not pin the registry version it came from",
            str(requireObj(REGISTRY)["version"]), str(contract["generated_from_registry_version"]))
        assertEquals(sha256(REGISTRY), str(contract["generated_from_registry_sha256"]))
        val policy = contract["generation_policy"] as JsonObject
        assertTrue("runs must be regenerated, not carried",
            list(policy["regenerated_from_registry"]).contains("runs"))
        assertTrue("record_schemas must be regenerated, not carried",
            list(policy["regenerated_from_registry"]).contains("record_schemas"))
        assertTrue("the contract does not forbid restating identity",
            str(policy["identity_may_not_be_restated"])?.contains("registry") == true)
    }

    @Test
    fun `the contract refuses an unregistered schema, fail-closed`() {
        val policy = requireObj(CROSS_RUN)["schema_admission_policy"] as JsonObject
        assertEquals(true, bool(policy["fail_closed"]))
        assertEquals("inferring a metric location from a record's shape is defect A",
            true, bool(policy["infer_from_shape_forbidden"]))
        assertEquals(true, bool(policy["fallback_to_previous_version_forbidden"]))
        listOf("UNSUPPORTED_SCHEMA", "UNREADABLE_SCHEMA", "UNSUPPORTED_VERSION").forEach { code ->
            assertTrue("$code is not a declared failure code",
                list(policy["failure_codes"]).contains(code))
        }
    }

    @Test
    fun `the three v9 runs are disjoint in id, namespace and marker`() {
        val runs = registryRuns()
        val ids = listOf(
            str(runs.getValue("K9")["host_run_id"]),
            str(runs.getValue("D9")["device_run_id"]),
            str(runs.getValue("V9_SMOKE")["smoke_run_id"]),
        ).filterNotNull()
        assertEquals(3, ids.size)
        ids.forEach { one ->
            ids.forEach { other ->
                if (one != other) assertTrue("run id '$one' contains '$other'", !one.contains(other))
            }
        }
        assertTrue("a v9 run id does not name v9", ids.all { it.contains("_V9_") })

        val namespaces = listOf(
            str(runs.getValue("K9")["host_output_namespace"]),
            str(runs.getValue("D9")["device_official_namespace"]),
            str(runs.getValue("V9_SMOKE")["device_smoke_namespace"]),
        ).filterNotNull()
        namespaces.forEach { one ->
            namespaces.forEach { other ->
                if (one != other) {
                    assertTrue("namespace '$one' is a prefix of '$other'", !other.startsWith(one))
                }
            }
        }
        val markers = listOf(
            str(runs.getValue("K9")["host_invocation_marker"]),
            str(runs.getValue("D9")["device_invocation_marker"]),
            str(runs.getValue("V9_SMOKE")["smoke_invocation_marker"]),
        ).filterNotNull()
        assertEquals("two v9 runs share a marker", markers.size, markers.distinct().size)
    }

    @Test
    fun `only the smoke run may be capped and it consumes no official invocation`() {
        val runs = requireObj(RUN_SEPARATION)["runs"] as JsonObject
        assertEquals(setOf("K9", "D9", "SMOKE"), runs.keys)
        assertEquals(false, bool(runs["SMOKE"]!!.jsonObject["consumes_official_invocation"]))
        assertEquals(true, bool(runs["SMOKE"]!!.jsonObject["scenario_limit_allowed"]))
        assertEquals(false, bool(runs["K9"]!!.jsonObject["scenario_limit_allowed"]))
        assertEquals(false, bool(runs["D9"]!!.jsonObject["scenario_limit_allowed"]))
        assertEquals(true, bool(runs["K9"]!!.jsonObject["consumes_official_invocation"]))
        assertEquals(true, bool(runs["D9"]!!.jsonObject["consumes_official_invocation"]))
        assertEquals("K9 is a keyword-only host baseline and measures no model",
            false, bool(runs["K9"]!!.jsonObject["measures_actual_model"]))
    }

    @Test
    fun `the evidence path policy forbids collisions and post-run writes into frozen roots`() {
        val policy = requireObj(EVIDENCE_PATHS)
        val rules = policy["collision_rules"] as JsonObject
        listOf("casefold_uniqueness", "unicode_normalization_uniqueness",
            "case_only_variants_forbidden").forEach { rule ->
            assertTrue("the policy declares no $rule", rules.containsKey(rule))
        }
        assertEquals("CASEFOLD_COLLISION",
            str(at(policy, "collision_rules/casefold_uniqueness/failure_code")))
        assertEquals(false,
            bool(at(policy, "output_roots/frozen_input/post_run_writes_allowed")))
        assertEquals(true,
            bool(at(policy, "output_roots/post_run_report/post_run_writes_allowed")))
        assertEquals("an authority artefact may never be regenerated",
            false, bool(at(policy, "artifact_classes/authority/may_be_regenerated")))
        assertEquals(true, bool(at(policy, "artifact_classes/derived/may_be_regenerated")))
        assertTrue("the policy does not name the enforcing tool",
            str(at(policy, "enforcement/tool"))?.contains("verify_evidence_paths_v9") == true)
    }

    @Test
    fun `the historical freeze policy covers v8 and still refuses edits to frozen files`() {
        val policy = requireObj(FREEZE_POLICY)
        val versions = (policy["historical_freezes"] as JsonArray)
            .map { str(it.jsonObject["version"]) }
        assertTrue("the policy does not cover the v8 freeze", versions.contains("v8"))
        assertTrue("v9 must be in the version ordering",
            list(policy["version_ordering"]).contains("v9"))
        val excused = list(policy["what_this_policy_does_not_excuse"])
        assertTrue("the policy would excuse a changed digest",
            excused.any { it.contains("changed digest") })
        assertTrue("the policy would excuse editing the v8 blocker",
            excused.any { it.contains("POST_RUN_BLOCKER") })
        val patterns = list(at(policy, "later_version_namespace_patterns/patterns"))
        assertTrue("a later-version pattern covers app/src/main, which would let a production "
            + "change hide behind a version bump",
            patterns.none { it.startsWith("app/src/main") })
    }

    @Test
    fun `the path policy names v9 as current and v8 as previous`() {
        val policy = requireObj(PATH_POLICY)
        assertEquals("v9", str(policy["current_version"]))
        assertTrue("v8 is not a previous version token",
            list(policy["previous_version_tokens"]).contains("v8"))
        assertTrue("v9 is listed as previous, which would refuse its own paths",
            !list(policy["previous_version_tokens"]).contains("v9"))
        listOf("RUN_K7", "RUN_K8", "RUN_D7", "RUN_D8").forEach { token ->
            assertTrue("$token is not a previous run token",
                list(policy["previous_run_tokens"]).contains(token))
        }
    }

    @Test
    fun `the acceptance contract keeps its non-verdict measurement states`() {
        val contract = requireObj(ACCEPTANCE)
        val text = contract.toString()
        listOf("MEASUREMENT_ONLY", "THRESHOLD_NOT_PREAUTHORIZED",
            "pinned_but_not_applicable", "generated_response_count").forEach { state ->
            assertTrue("the acceptance contract no longer carries $state", text.contains(state))
        }
        assertEquals("RYEONG_PRODUCTION_COMPATIBILITY_V9_RUN_D9_DEVICE_ACTUAL_MODEL_BASELINE",
            str(contract["applies_to_run"]))
    }

    @Test
    fun `the frozen v7 historical failure contract is carried unchanged`() {
        val contract = requireObj(HISTORICAL_FAILURE)
        val entries = contract["expected_historical_failures"] as JsonArray
        assertEquals("exactly one declared historical failure", 1, entries.size)
        val conditions = entries[0].jsonObject["match_conditions"] as JsonObject
        assertEquals(3, int(conditions["expected_value"]))
        assertEquals(5, int(conditions["actual_value"]))
        assertEquals("the pinned v7 source digest is stale",
            sha256(str(conditions["source_path"])!!), str(conditions["source_sha256"]))
        assertTrue("the contract must still refuse 'ALL TESTS GREEN'",
            list(contract["forbidden_verdict_language"]).contains("ALL TESTS GREEN"))
    }

    @Test
    fun `the case table declares the units and forbids passing by assertion`() {
        val table = requireObj(CASE_TABLE)
        val units = table["unit_definitions"] as JsonObject
        listOf("run_identity", "parity", "single_source_drift").forEach { unit ->
            assertTrue("the case table does not define $unit", units.containsKey(unit))
        }
        val expected = table["expected_real_values"] as JsonObject
        assertEquals("ryeong_v8_official_result/v1", str(expected["k8_host_result_schema"]))
        assertEquals("ryeong_v7_official_result/v1",
            str(expected["v8_contract_expected_k8_schema"]))
        assertEquals("VALID BASELINE", str(expected["k8_run_local_validity"]))
        assertEquals("HOLD BEFORE V8 DEVICE PREFLIGHT", str(expected["v8_version_verdict"]))
        assertEquals(0, int(expected["k7_invocations"]))
        assertTrue("the case table does not forbid a registry that only agrees with itself",
            str(expected["not_hard_coded"])?.contains("only agrees with itself") == true)
    }
}
