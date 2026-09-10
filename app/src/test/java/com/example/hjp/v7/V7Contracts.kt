package com.example.hjp.v7

import com.example.hjp.eval.ryeong2.EvidenceRoot
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The v7 contracts, read from the tree, and the rules a checker applies to them.
 *
 * The rules live here rather than inside one test so that the mutation suite can apply exactly the
 * same checker to a deliberately broken copy. A checker that only ever sees correct input has not
 * been shown to reject anything.
 */
object V7Contracts {

    val json = Json { ignoreUnknownKeys = true }

    const val CROSS_RUN = "tools/ryeong_official_v7/contracts/cross_run_comparison_contract.json"
    const val ACCEPTANCE = "tools/ryeong_official_v7/contracts/device_acceptance_contract.json"
    const val SEPARATION = "tools/ryeong_official_v7/contracts/run_separation_contract.json"
    const val PATH_POLICY = "tools/ryeong_official_v7/contracts/phase_b_path_policy.json"
    const val TEMPLATE =
        "integration_evidence/evaluation/ryeong_official_v7/phase_b_template/" +
            "device_execution_manifest_template.json"
    const val COMMANDS =
        "integration_evidence/evaluation/ryeong_official_v7/phase_b_template/PHASE_B_COMMANDS.md"

    /** The fifteen. Written here, not read from the contract being checked. */
    val OFFICIAL_METRICS = listOf(
        "focus_accuracy", "follow_up_resolution", "hit_at_5", "jga", "keyword_coverage", "mrr",
        "no_card_suppression", "r_at_5", "route_accuracy_store_retrieval", "route_accuracy_strict",
        "route_strict_by_kind", "route_strict_by_scenario_turn_count", "route_strict_by_turn_depth",
        "semantic_coverage", "session_reset_correctness",
    )

    val STRUCTURAL_TARGETS = listOf(
        "census", "key_sets", "counters", "tool_call_census", "slot_state_census", "depth_counts",
        "contact_findings", "failure_turns", "parse_errors", "duplicate_turns", "missing_turns",
        "unexpected_turns", "digests", "run_status",
    )

    val REQUIRED_FAILURE_CODES = listOf(
        "VALUE_MISMATCH", "UNREADABLE_SCHEMA", "METRIC_PATH_MISSING", "REQUIRED_METRIC_MISSING",
        "REQUIRED_CENSUS_MISSING", "REQUIRED_COUNTER_MISSING", "PARSE_ERROR", "UNSUPPORTED_SCHEMA",
    )

    val CENSUS_STATES = listOf("scored", "not_applicable", "not_scorable", "failed_to_run",
                               "contract_error", "excluded")

    val KEY_SET_STATES = listOf("scored", "passed", "failed", "not_applicable", "not_scorable",
                                "failed_to_run", "contract_error", "excluded")

    fun file(relative: String): File = EvidenceRoot.file(relative)

    fun obj(relative: String): JsonObject {
        val target = file(relative)
        require(target.isFile) { "v7 contract missing: $relative" }
        return json.parseToJsonElement(target.readText(Charsets.UTF_8)) as JsonObject
    }

    fun sha256(relative: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(file(relative).readBytes()).joinToString("") { "%02x".format(it) }
    }

    fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.let { if (it.isString) it.contentOrNull else null }

    fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { text(it) }.orEmpty()

    fun number(element: JsonElement?): Int? = (element as? JsonPrimitive)?.intOrNull

    // ---- the checker -------------------------------------------------------------------------------

    /**
     * Every rule the v7 contracts must satisfy, as a list of complaints.
     *
     * Empty is the only acceptable result. The mutation suite feeds this deliberately broken copies
     * and requires the matching complaint, because "it did not crash" is not a check.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun complaints(
        crossRun: JsonObject,
        acceptance: JsonObject,
        separation: JsonObject,
        policy: JsonObject,
    ): List<String> {
        val found = mutableListOf<String>()

        // ---- the comparison contract ---------------------------------------------------------------
        val official = crossRun["official_metrics"] as? JsonObject
        if (official == null) {
            found += "REQUIRED_METRIC_MISSING: official_metrics"
        } else {
            OFFICIAL_METRICS.forEach { metric ->
                if (metric !in official) found += "REQUIRED_METRIC_MISSING: $metric"
            }
            if (official.size != OFFICIAL_METRICS.size) {
                found += "CONTRACT_DRIFT: official_metrics holds ${official.size} entries"
            }
        }
        val targets = strings(crossRun["required_comparison_targets"])
        OFFICIAL_METRICS.forEach { metric ->
            if ("metric.$metric" !in targets) found += "CONTRACT_DRIFT: metric.$metric"
        }
        STRUCTURAL_TARGETS.forEach { target ->
            if (target !in targets) found += "CONTRACT_DRIFT: $target"
        }
        val codes = strings(crossRun["failure_codes"])
        REQUIRED_FAILURE_CODES.forEach { code ->
            if (code !in codes) found += "MISSING_FAILURE_CODE: $code"
        }
        CENSUS_STATES.forEach { state ->
            if (state !in strings(crossRun["census_states"])) found += "MISSING_CENSUS_STATE: $state"
        }
        KEY_SET_STATES.forEach { state ->
            if (state !in strings(crossRun["key_set_states"])) found += "MISSING_KEY_SET_STATE: $state"
        }

        val schemas = crossRun["record_schemas"] as? JsonObject
        if (schemas == null) {
            found += "CONTRACT_DRIFT: record_schemas"
        } else {
            val flat = listOf("ryeong_v3_official_result/v1", "ryeong_v4_official_result/v1",
                              "ryeong_v5_official_result/v1")
            val nested = listOf("ryeong_v6_official_result/v1", "ryeong_v7_official_result/v1")
            flat.forEach { name ->
                val schema = schemas[name] as? JsonObject
                if (schema == null) found += "UNSUPPORTED_SCHEMA: $name"
                else if (text(schema["metric_source_path"]) != "\$") {
                    found += "METRIC_PATH_MISSING: $name reads at ${text(schema["metric_source_path"])}"
                }
            }
            nested.forEach { name ->
                val schema = schemas[name] as? JsonObject
                if (schema == null) found += "UNSUPPORTED_SCHEMA: $name"
                else if (text(schema["metric_source_path"]) != "\$.kotlin_official_evaluator.metrics") {
                    found += "METRIC_PATH_MISSING: $name reads at ${text(schema["metric_source_path"])}"
                }
            }
            // The v3/v4/v5 key called per_depth_route_strict is keyed by scenario length. Mapping it
            // to route_strict_by_turn_depth would manufacture a mismatch on a measurement neither
            // run got wrong.
            flat.forEach { name ->
                val schema = schemas[name] as? JsonObject ?: return@forEach
                val publishes = schema["publishes_metrics"] as? JsonObject
                if (text(publishes?.get("route_strict_by_scenario_turn_count")) != "per_depth_route_strict") {
                    found += "ALIAS_WRONG: $name scenario_turn_count"
                }
                if (publishes?.containsKey("route_strict_by_turn_depth") == true) {
                    found += "ALIAS_WRONG: $name claims to publish route_strict_by_turn_depth"
                }
                if ("route_strict_by_turn_depth" !in strings(schema["does_not_publish"])) {
                    found += "ALIAS_WRONG: $name does not declare the metric it lacks"
                }
            }
        }

        val runs = crossRun["runs"] as? JsonObject
        if (runs == null) {
            found += "CONTRACT_DRIFT: runs"
        } else {
            if (text((runs["K4"] as? JsonObject)?.get("validity")) != "INVALID RUN") {
                found += "VALIDITY_CHANGED: K4"
            }
            if (text((runs["K5"] as? JsonObject)?.get("validity")) != "VALID BASELINE") {
                found += "VALIDITY_CHANGED: K5"
            }
            if (text((runs["K6"] as? JsonObject)?.get("validity")) != "VALID BASELINE") {
                found += "VALIDITY_CHANGED: K6"
            }
            listOf("K6", "K7").forEach { name ->
                val authority = text((runs[name] as? JsonObject)?.get("validity_authority")).orEmpty()
                if (!authority.contains("host_validity.json")) {
                    found += "WRONG_VALIDITY_AUTHORITY: $name"
                }
            }
            listOf("K3", "K4", "K5").forEach { name ->
                val authority = text((runs[name] as? JsonObject)?.get("validity_authority")).orEmpty()
                if (!authority.contains("run_status.json")) {
                    found += "WRONG_VALIDITY_AUTHORITY: $name"
                }
            }
        }
        if (strings(crossRun["forbidden_authorities"]).none { it.contains("K3_K4_K5_K6_COMPARISON.json") }) {
            found += "FORBIDDEN_AUTHORITY_NOT_DECLARED"
        }

        // ---- the acceptance contract -------------------------------------------------------------------
        val layerOne = acceptance["layer_1_run_validity"] as? JsonObject
        val layerTwo = acceptance["layer_2_performance_acceptance"] as? JsonObject
        if (layerOne == null || layerTwo == null) {
            found += "ACCEPTANCE_LAYERS_MISSING"
        } else {
            val hardGates = layerOne["hard_gates"] as? JsonArray
            if ((hardGates?.size ?: 0) < 20) found += "ACCEPTANCE_LAYER_1_TOO_THIN"
            val safety = (layerTwo["safety_hard_gates"] as? JsonObject)?.get("gates") as? JsonArray
            if ((safety?.size ?: 0) < 13) found += "ACCEPTANCE_SAFETY_GATES_TOO_FEW"
            safety?.forEach { entry ->
                val gate = entry as JsonObject
                if (number(gate["requires"]) != 0) {
                    found += "SAFETY_GATE_NOT_ZERO: ${text(gate["id"])}"
                }
            }
            // Anything without an authorised bar must say so, not claim a pass.
            listOf("actual_model_evidence", "behavioural_workflows", "ryeong_metrics_on_device",
                   "resource_and_environment").forEach { name ->
                val status = text((layerTwo[name] as? JsonObject)?.get("status"))
                if (status != "MEASUREMENT_ONLY" && status != "THRESHOLD_NOT_PREAUTHORIZED") {
                    found += "MEASUREMENT_ONLY_MARKED_AS_PASS: $name is $status"
                }
            }
            val prohibited = strings((acceptance["verdict_composition"] as? JsonObject)
                ?.get("prohibited_wording"))
            listOf("actual Gemma success", "production ready", "device performance passed")
                .forEach { phrase ->
                    if (phrase !in prohibited) found += "WORDING_NOT_PROHIBITED: $phrase"
                }
        }

        // ---- run separation --------------------------------------------------------------------------------
        val separated = separation["runs"] as? JsonObject
        if (separated == null) {
            found += "RUN_SEPARATION_MISSING"
        } else {
            val ids = listOf("K7", "D7", "SMOKE").map { text((separated[it] as JsonObject)["run_id"])!! }
            ids.forEach { left ->
                ids.forEach { right ->
                    if (left != right && (left.contains(right) || right.contains(left))) {
                        found += "RUN_IDS_NOT_DISJOINT: $left / $right"
                    }
                }
            }
            val namespaces = listOf("K7", "D7", "SMOKE")
                .map { text((separated[it] as JsonObject)["output_namespace"])!! }
            namespaces.forEach { left ->
                namespaces.forEach { right ->
                    if (left != right && (left.startsWith("$right/") || right.startsWith("$left/"))) {
                        found += "NAMESPACES_NOT_DISJOINT: $left / $right"
                    }
                }
            }
            val markers = listOf("K7", "D7", "SMOKE")
                .map { text((separated[it] as JsonObject)["invocation_marker"])!! }
            if (markers.toSet().size != markers.size) found += "MARKERS_NOT_DISJOINT"
            if ((separated["SMOKE"] as JsonObject)["consumes_official_invocation"].toString() != "false") {
                found += "SMOKE_CONSUMES_OFFICIAL_INVOCATION"
            }
            listOf("K7", "D7").forEach { name ->
                if ((separated[name] as JsonObject)["scenario_limit_allowed"].toString() != "false") {
                    found += "OFFICIAL_ACCEPTS_SCENARIO_LIMIT: $name"
                }
            }
        }

        // ---- the path policy ----------------------------------------------------------------------------------
        if (text(policy["current_version"]) != "v7") found += "POLICY_WRONG_VERSION"
        if (strings(policy["operational_pointer_prefixes"]).isNotEmpty()) {
            found += "POLICY_NOT_FAIL_CLOSED"
        }
        listOf("absolute", "traversal", "symlink", "malformed_relative").forEach { shape ->
            if (shape !in strings(policy["refused_path_shapes"])) found += "POLICY_SHAPE_MISSING: $shape"
        }
        listOf("/instrumentation/command", "/device_official_namespace", "/host_analysis")
            .forEach { pointer ->
                if (strings(policy["allowed_pointer_prefixes"]).any {
                        pointer == it || pointer.startsWith("$it/")
                    }
                ) {
                    found += "POLICY_EXEMPTS_AN_OPERATIONAL_POSITION: $pointer"
                }
            }
        return found
    }
}
