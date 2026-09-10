package com.example.hjp.v8

import com.example.hjp.v8.V8Contracts.ACCEPTANCE
import com.example.hjp.v8.V8Contracts.CARDINALITY
import com.example.hjp.v8.V8Contracts.CASE_TABLE
import com.example.hjp.v8.V8Contracts.HISTORICAL_FAILURE
import com.example.hjp.v8.V8Contracts.HISTORICAL_FREEZE_POLICY
import com.example.hjp.v8.V8Contracts.PATH_POLICY
import com.example.hjp.v8.V8Contracts.RUN_SEPARATION
import com.example.hjp.v8.V8Contracts.V7_ACCEPTANCE
import com.example.hjp.v8.V8Contracts.at
import com.example.hjp.v8.V8Contracts.bool
import com.example.hjp.v8.V8Contracts.everyString
import com.example.hjp.v8.V8Contracts.int
import com.example.hjp.v8.V8Contracts.list
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
 * The v8 contracts, read as documents rather than as intentions.
 *
 * Each of these files is a promise about how v8 behaves, and a promise nothing checks is a comment.
 * So: the two cardinality units stay separate, the historical-failure contract keeps every match
 * condition it needs to stay narrow, the three run identities stay disjoint, and the acceptance
 * contract's *meaning* is the v7 one with only the version-bearing strings moved.
 *
 * The last is the one worth stating plainly. §11 allows the version, the run ids, the paths and the
 * digest references to change, and nothing else. A threshold quietly relaxed while everything else
 * moved would be invisible in a diff full of v7→v8 renames, so it is checked here by counting rather
 * than by reading.
 */
class V8ContractTest {

    // ==============================================================================================
    // cardinality
    // ==============================================================================================

    @Test
    fun `the cardinality contract keeps the two units in separate fields`() {
        val contract = requireObj(CARDINALITY)
        assertEquals("occurrence count", 5, int(contract["operational_occurrence_count"]))
        assertEquals("distinct target count", 3, int(contract["distinct_invalid_target_count"]))
        assertEquals("the occurrence pointer list is not the occurrence count",
            5, list(contract["operational_occurrence_pointers"]).size)
        assertEquals("the target list is not the target count",
            3, list(contract["distinct_invalid_targets"]).size)
        assertTrue(
            "the two counts are equal, so nothing in this contract distinguishes them",
            int(contract["operational_occurrence_count"]) !=
                int(contract["distinct_invalid_target_count"]),
        )
    }

    @Test
    fun `the cardinality contract's mapping joins the two counts`() {
        val contract = requireObj(CARDINALITY)
        val mapping = contract["pointer_to_target"] as JsonObject
        val pointers = list(contract["operational_occurrence_pointers"])
        val targets = list(contract["distinct_invalid_targets"])

        assertEquals("the mapping does not cover the pointer list",
            pointers.sorted(), mapping.keys.sorted())
        assertEquals("the mapping's range is not the target list",
            targets.sorted(), mapping.values.mapNotNull { str(it) }.distinct().sorted())
        assertEquals("the mapping is not the size of the occurrence list", 5, mapping.size)

        val byTarget = mapping.entries.groupBy { str(it.value) }
        assertEquals("DEVICE_SCORE.json must be named from two pointers",
            2, byTarget["DEVICE_SCORE.json"]?.size)
        assertEquals("DEVICE_RECOMPUTATION.json must be named from two pointers",
            2, byTarget["DEVICE_RECOMPUTATION.json"]?.size)
        assertEquals("v5 must be named from one pointer", 1, byTarget["v5"]?.size)
    }

    @Test
    fun `the cardinality contract records every occurrence in full`() {
        val contract = requireObj(CARDINALITY)
        val detail = contract["occurrence_detail"] as JsonArray
        assertEquals("one row per occurrence", 5, detail.size)
        detail.map { it.jsonObject }.forEach { row ->
            listOf("json_pointer", "position_classification", "detected_token",
                "normalized_target", "violation_code", "source_value", "why_operational")
                .forEach { field ->
                    assertTrue("an occurrence row omits $field", !str(row[field]).isNullOrBlank())
                }
            assertEquals("every occurrence must be in an operational position",
                "OPERATIONAL", str(row["position_classification"]))
        }
    }

    @Test
    fun `the cardinality contract carries the v7 misclassification and all three counts`() {
        val historical = at(requireObj(CARDINALITY), "historical_v7_misclassification")
            as? JsonObject
        assertNotNull("the contract does not record the v7 misclassification", historical)
        assertEquals(3, int(historical!!["expected_distinct_invalid_target_count"]))
        assertEquals(3, int(historical["observed_distinct_invalid_target_count"]))
        assertEquals(3, int(historical["incorrect_frozen_operational_occurrence_expectation"]))
        assertEquals(5, int(historical["observed_operational_occurrence_count"]))
        assertEquals("V7_CHARACTERIZATION_ORACLE_UNIT_CONFLATION", str(historical["finding_code"]))
        assertEquals("DISTINCT_TARGET_COUNT_USED_AS_OCCURRENCE_COUNT",
            str(historical["reason_code"]))

        val three = historical["three_numbers_for_one_fact"] as? JsonObject
        assertNotNull(
            "the contract does not record that v7 produced three different counts for one defect",
            three,
        )
        listOf("3", "5", "6").forEach { number ->
            assertTrue("the contract does not explain the count $number",
                !str(three!![number]).isNullOrBlank())
        }
        assertEquals("the sixth mention is the one in an allowed position",
            1, int(historical["mentions_in_allowed_positions"]))
        assertEquals("total mentions including allowed positions",
            6, int(historical["total_mentions_including_allowed_positions"]))
    }

    @Test
    fun `the cardinality contract pins the digests it describes`() {
        val contract = requireObj(CARDINALITY)
        assertEquals("the pinned v6 template digest is stale",
            sha256(str(contract["source_template_path"])!!), str(contract["source_template_sha"]))
        assertEquals("the pinned python reader digest is stale",
            sha256(str(contract["python_reader_path"])!!), str(contract["python_reader_sha"]))
        assertEquals("the pinned kotlin reader digest is stale",
            sha256(str(contract["kotlin_reader_path"])!!), str(contract["kotlin_reader_sha"]))
    }

    @Test
    fun `the cardinality contract forbids collapsing the two units`() {
        val prohibitions = list(requireObj(CARDINALITY)["prohibitions"])
        listOf("merge", "deduplicate occurrences by target", "hard-code").forEach { fragment ->
            assertTrue(
                "the contract does not prohibit $fragment",
                prohibitions.any { it.contains(fragment) },
            )
        }
    }

    // ==============================================================================================
    // historical failure
    // ==============================================================================================

    @Test
    fun `the historical failure contract declares exactly one expected failure`() {
        val contract = requireObj(HISTORICAL_FAILURE)
        val entries = contract["expected_historical_failures"] as JsonArray
        assertEquals(
            "more than one declared historical failure would make 'exactly one' unenforceable",
            1, entries.size,
        )
        val entry = entries[0].jsonObject
        assertEquals("V7_CHARACTERIZATION_ORACLE_UNIT_CONFLATION", str(entry["finding_code"]))
        assertEquals(true, bool(entry["all_conditions_required"]))
    }

    @Test
    fun `the historical failure contract's match conditions are narrow enough to be a match`() {
        val entry = (requireObj(HISTORICAL_FAILURE)["expected_historical_failures"]
            as JsonArray)[0].jsonObject
        val conditions = entry["match_conditions"] as JsonObject
        listOf(
            "suite", "testcase", "failure_type", "failure_message_contains",
            "expected_value", "actual_value",
            "source_path", "source_sha256", "fixture_path", "fixture_sha256",
            "case_table_path", "case_table_sha256",
            "occurrence_pointer_set", "occurrence_pointer_count",
            "distinct_target_set", "distinct_target_count", "pointer_to_target",
        ).forEach { field ->
            assertTrue("the contract can be matched without checking $field",
                conditions.containsKey(field))
        }
        assertEquals(5, int(conditions["occurrence_pointer_count"]))
        assertEquals(3, int(conditions["distinct_target_count"]))
        assertEquals(3, int(conditions["expected_value"]))
        assertEquals(5, int(conditions["actual_value"]))
    }

    @Test
    fun `the pinned frozen digests are the files in the tree`() {
        val conditions = ((requireObj(HISTORICAL_FAILURE)["expected_historical_failures"]
            as JsonArray)[0].jsonObject["match_conditions"]) as JsonObject
        listOf("source" to "source_path", "fixture" to "fixture_path",
            "case_table" to "case_table_path").forEach { (prefix, pathKey) ->
            val relative = str(conditions[pathKey])!!
            assertEquals(
                "the pinned $prefix digest is not the file in the tree: $relative",
                sha256(relative), str(conditions["${prefix}_sha256"]),
            )
        }
    }

    @Test
    fun `the historical failure contract refuses the language that would hide the failure`() {
        val contract = requireObj(HISTORICAL_FAILURE)
        val forbidden = list(contract["forbidden_verdict_language"])
        listOf("FULL JVM FAILURES 0", "ALL TESTS GREEN").forEach { phrase ->
            assertTrue("the contract permits the phrase '$phrase'", forbidden.contains(phrase))
        }
        val required = list(contract["required_verdict_language"])
        assertTrue("the contract does not require the raw failure to be stated",
            required.any { it.contains("1 frozen historical failure") })
        assertTrue("the contract does not require the conformance verdict to be stated",
            required.any { it.contains("0 blocking failures") })

        val raw = at(contract, "pass_conditions/raw") as JsonObject
        assertEquals("the raw exit code must be pinned to 1, not to 0", 1, int(raw["raw_exit_code"]))
        assertEquals("raw failures must be pinned to 1", 1, int(raw["raw_failures"]))
        assertEquals("raw errors must be pinned to 0", 0, int(raw["raw_errors"]))
        assertEquals("RAW_JVM_HISTORICAL_EXPECTED_FAILURE_ONLY", str(raw["verdict_when_met"]))

        val current = at(contract, "pass_conditions/current") as JsonObject
        assertEquals(0, int(current["current_blocking_failures"]))
        assertEquals("CURRENT_V8_CONFORMANCE_PASS", str(current["verdict_when_met"]))
    }

    @Test
    fun `absence of the historical failure is not treated as success`() {
        val classification = at(requireObj(HISTORICAL_FAILURE), "classification") as JsonObject
        assertTrue(
            "the contract does not say that a missing historical failure blocks",
            str(classification["absence_is_not_success"])?.contains("blocks") == true,
        )
        assertTrue(
            "the contract does not forbid two failures matching one entry",
            str(classification["one_entry_one_failure"])?.contains("at most once") == true,
        )
    }

    // ==============================================================================================
    // historical freeze policy
    // ==============================================================================================

    @Test
    fun `the historical freeze policy separates an edit from a later namespace`() {
        val policy = requireObj(HISTORICAL_FREEZE_POLICY)
        val codes = (policy["addition_classification"] as JsonArray)
            .map { str(it.jsonObject["code"]) }
        assertTrue("POST_SNAPSHOT_VERSION_NAMESPACE is not a classification",
            codes.contains("POST_SNAPSHOT_VERSION_NAMESPACE"))
        assertTrue("UNDECLARED_INSIDE_FROZEN_NAMESPACE is not a classification",
            codes.contains("UNDECLARED_INSIDE_FROZEN_NAMESPACE"))

        val excused = list(policy["what_this_policy_does_not_excuse"])
        assertTrue("the policy would excuse a changed digest",
            excused.any { it.contains("changed digest") })
        assertTrue("the policy would excuse an edit to the frozen v7 characterization",
            excused.any { it.contains("v7char") })

        val patterns = list(at(policy, "later_version_namespace_patterns/patterns"))
        assertTrue(
            "the later-version patterns cover app/src/main, which would let production change " +
                "hide behind a version bump",
            patterns.none { it.startsWith("app/src/main") },
        )
    }

    // ==============================================================================================
    // run separation
    // ==============================================================================================

    @Test
    fun `the three v8 runs are disjoint in id, namespace and marker`() {
        val separation = requireObj(RUN_SEPARATION)
        val runs = separation["runs"] as JsonObject
        assertEquals("K8, D8 and SMOKE", setOf("K8", "D8", "SMOKE"), runs.keys)

        val ids = runs.values.map { str(it.jsonObject["run_id"])!! }
        ids.forEach { one ->
            ids.forEach { other ->
                if (one != other) {
                    assertTrue("run id '$one' contains '$other'", !one.contains(other))
                }
            }
        }
        assertTrue("a run id does not name v8", ids.all { it.contains("_V8_") })

        val namespaces = runs.values.map { str(it.jsonObject["output_namespace"])!! }
        namespaces.forEach { one ->
            namespaces.forEach { other ->
                if (one != other) {
                    assertTrue("namespace '$one' is a prefix of '$other'", !other.startsWith(one))
                }
            }
        }
        val markers = runs.values.mapNotNull { str(it.jsonObject["invocation_marker"]) }
        assertEquals("two runs share an invocation marker", markers.size, markers.distinct().size)
    }

    @Test
    fun `only the two official runs consume an invocation, and only the smoke run may be capped`() {
        val runs = requireObj(RUN_SEPARATION)["runs"] as JsonObject
        assertEquals("the smoke run must not consume the official invocation",
            false, bool(runs["SMOKE"]!!.jsonObject["consumes_official_invocation"]))
        assertEquals("K8 must consume its own invocation",
            true, bool(runs["K8"]!!.jsonObject["consumes_official_invocation"]))
        assertEquals("D8 must consume its own invocation",
            true, bool(runs["D8"]!!.jsonObject["consumes_official_invocation"]))
        assertEquals("only the smoke run may cap the scenario count",
            true, bool(runs["SMOKE"]!!.jsonObject["scenario_limit_allowed"]))
        assertEquals("K8 must refuse a scenario limit",
            false, bool(runs["K8"]!!.jsonObject["scenario_limit_allowed"]))
        assertEquals("D8 must refuse a scenario limit",
            false, bool(runs["D8"]!!.jsonObject["scenario_limit_allowed"]))
        assertEquals("K8 is a keyword-only host baseline and measures no model",
            false, bool(runs["K8"]!!.jsonObject["measures_actual_model"]))
    }

    // ==============================================================================================
    // acceptance — meaning unchanged, identifiers moved
    // ==============================================================================================

    @Test
    fun `the v8 acceptance contract is the v7 one with only the identifiers moved`() {
        val v8 = requireObj(ACCEPTANCE)
        val v7 = requireObj(V7_ACCEPTANCE)

        // Normalise the version-bearing strings on the v7 side and compare the documents as text.
        // A threshold relaxed while everything else moved would be invisible in a diff full of
        // v7→v8 renames; this makes it a single equality.
        fun normalise(text: String): String = text
            .replace("_V7_", "_V8_").replace("_v7_", "_v8_")
            .replace("-v7-", "-v8-").replace("/v7/", "/v8/")
            .replace("RUN_K7", "RUN_K8").replace("RUN_D7", "RUN_D8")
            .replace("K7", "K8").replace("D7", "D8")
            .replace("ryeong_official_v7", "ryeong_official_v8")
            .replace("V7", "V8").replace("v7", "v8")

        val v8Numbers = everyString(v8).filter { it.toDoubleOrNull() != null }
        val v7Numbers = everyString(v7).filter { it.toDoubleOrNull() != null }
        assertEquals("a numeric value moved between the v7 and v8 acceptance contracts",
            v7Numbers.sorted(), v8Numbers.sorted())

        val v8Strings = everyString(v8).map { normalise(it) }.sorted()
        val v7Strings = everyString(v7).map { normalise(it) }.sorted()
        val onlyInV8 = v8Strings - v7Strings.toSet()
        val onlyInV7 = v7Strings - v8Strings.toSet()
        assertEquals(
            "the v8 acceptance contract says something the v7 one did not, after normalising the " +
                "version strings: $onlyInV8",
            emptyList<String>(), onlyInV8,
        )
        assertEquals(
            "the v8 acceptance contract dropped something the v7 one said: $onlyInV7",
            emptyList<String>(), onlyInV7,
        )
    }

    @Test
    fun `the acceptance contract keeps its non-verdict measurement states`() {
        val text = requireObj(ACCEPTANCE).toString()
        listOf("MEASUREMENT_ONLY", "THRESHOLD_NOT_PREAUTHORIZED",
            "pinned_but_not_applicable").forEach { state ->
            assertTrue("the acceptance contract no longer carries $state", text.contains(state))
        }
        assertTrue(
            "the acceptance contract no longer refuses generated_response_count",
            text.contains("generated_response_count"),
        )
        assertEquals("the contract must apply to RUN_D8",
            "RYEONG_PRODUCTION_COMPATIBILITY_V8_RUN_D8_DEVICE_ACTUAL_MODEL_BASELINE",
            str(requireObj(ACCEPTANCE)["applies_to_run"]))
    }

    // ==============================================================================================
    // path policy
    // ==============================================================================================

    @Test
    fun `the v8 path policy names v8 as current and v7 as previous`() {
        val policy = requireObj(PATH_POLICY)
        assertEquals("v8", str(policy["current_version"]))
        assertTrue("v7 is not a previous version token",
            list(policy["previous_version_tokens"]).contains("v7"))
        assertTrue("v8 is listed as a previous version token, which would refuse its own paths",
            !list(policy["previous_version_tokens"]).contains("v8"))
        assertTrue("RUN_K7 is not a previous run token",
            list(policy["previous_run_tokens"]).contains("RUN_K7"))

        list(policy["current_version_namespaces"]).forEach { namespace ->
            assertTrue("a current namespace names another version: $namespace",
                !Regex("(?<![A-Za-z0-9])v[1-7](?![A-Za-z0-9])").containsMatchIn(namespace))
        }
        list(policy["current_version_run_ids"]).forEach { id ->
            assertTrue("a current run id names another version: $id", id.contains("_V8_"))
        }
        assertEquals("the raw schema pin must stay at v6",
            "ryeong_v6_raw_turn/v1", str(at(policy, "pinned_exact_values/~raw_schema~expected")
                ?: (policy["pinned_exact_values"] as JsonObject)["/raw_schema/expected"]))
    }

    @Test
    fun `the case table declares both units and forbids hard-coding`() {
        val table = requireObj(CASE_TABLE)
        val units = table["unit_definitions"] as JsonObject
        assertTrue("the case table does not define an operational occurrence",
            !str(units["operational_occurrence"]).isNullOrBlank())
        assertTrue("the case table does not define a distinct invalid target",
            !str(units["distinct_invalid_target"]).isNullOrBlank())
        val expected = table["expected_real_values"] as JsonObject
        assertEquals(5, int(expected["v6_template_operational_occurrence_count"]))
        assertEquals(3, int(expected["v6_template_distinct_invalid_target_count"]))
        assertEquals(0, int(expected["v7_template_operational_occurrence_count"]))
        assertEquals(0, int(expected["v8_template_operational_occurrence_count"]))
        assertTrue("the case table does not forbid hard-coding the answer",
            str(expected["not_hard_coded"])?.contains("without traversing") == true)
    }
}
