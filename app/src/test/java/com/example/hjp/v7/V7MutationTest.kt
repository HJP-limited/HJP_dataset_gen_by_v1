package com.example.hjp.v7

import com.example.hjp.v7.V7Contracts.complaints
import com.example.hjp.v7.V7Contracts.obj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Break each contract on purpose and require the matching complaint.
 *
 * The Python mutation suite does this to the tools. This does it to the contracts, from a second
 * implementation, because two checkers that agree on correct input have shown nothing — the question
 * is whether they agree on input that is wrong, and in the same way.
 *
 * A mutation counts as detected only when the expected complaint appears. Any complaint at all would
 * be satisfied by a checker that complains about everything.
 */
class V7MutationTest {

    private data class Mutation(
        val id: String,
        val why: String,
        val expect: String,
        val apply: (MutableMap<String, JsonObject>) -> Unit,
    )

    private fun mutate(document: JsonObject, path: List<String>, value: JsonElement?): JsonObject {
        if (path.isEmpty()) return document
        val key = path.first()
        val entries = document.toMutableMap()
        if (path.size == 1) {
            if (value == null) entries.remove(key) else entries[key] = value
        } else {
            val child = entries[key]
            require(child is JsonObject) { "cannot descend into $key" }
            entries[key] = mutate(child, path.drop(1), value)
        }
        return JsonObject(entries)
    }

    private fun dropFromArray(document: JsonObject, key: String, value: String): JsonObject {
        val array = document[key] as JsonArray
        return mutate(document, listOf(key),
            JsonArray(array.filterNot { (it as? JsonPrimitive)?.contentOrNullSafe() == value }))
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else null

    private val mutations = listOf(
        Mutation("K01_official_metric_removed",
            "one of the fifteen stops being an official metric",
            "REQUIRED_METRIC_MISSING: jga") { docs ->
            docs["crossRun"] = mutate(docs["crossRun"]!!, listOf("official_metrics", "jga"), null)
        },
        Mutation("K02_jga_comparison_target_removed",
            "exactly what the v5 gate did: JGA simply is not on the list",
            "CONTRACT_DRIFT: metric.jga") { docs ->
            docs["crossRun"] = dropFromArray(docs["crossRun"]!!,
                "required_comparison_targets", "metric.jga")
        },
        Mutation("K03_key_set_target_removed",
            "key sets stop being compared, so equal counts pass for equal sets",
            "CONTRACT_DRIFT: key_sets") { docs ->
            docs["crossRun"] = dropFromArray(docs["crossRun"]!!,
                "required_comparison_targets", "key_sets")
        },
        Mutation("K04_census_target_removed",
            "the census stops being compared",
            "CONTRACT_DRIFT: census") { docs ->
            docs["crossRun"] = dropFromArray(docs["crossRun"]!!,
                "required_comparison_targets", "census")
        },
        Mutation("K05_counter_target_removed",
            "the runtime counters stop being compared",
            "CONTRACT_DRIFT: counters") { docs ->
            docs["crossRun"] = dropFromArray(docs["crossRun"]!!,
                "required_comparison_targets", "counters")
        },
        Mutation("K06_unreadable_failure_code_removed",
            "without the code there is nothing to report an unread run as",
            "MISSING_FAILURE_CODE: UNREADABLE_SCHEMA") { docs ->
            docs["crossRun"] = dropFromArray(docs["crossRun"]!!,
                "failure_codes", "UNREADABLE_SCHEMA")
        },
        Mutation("K07_k6_read_at_top_level",
            "the v6 defect: look for K6's metrics where K3 keeps them",
            "METRIC_PATH_MISSING: ryeong_v6_official_result/v1") { docs ->
            docs["crossRun"] = mutate(docs["crossRun"]!!,
                listOf("record_schemas", "ryeong_v6_official_result/v1", "metric_source_path"),
                JsonPrimitive("\$"))
        },
        Mutation("K08_per_depth_aliased_to_turn_depth",
            "v3's per_depth_route_strict is keyed by scenario length; calling it turn depth "
                + "manufactures a mismatch",
            "ALIAS_WRONG: ryeong_v3_official_result/v1 scenario_turn_count") { docs ->
            docs["crossRun"] = mutate(docs["crossRun"]!!,
                listOf("record_schemas", "ryeong_v3_official_result/v1", "publishes_metrics",
                    "route_strict_by_scenario_turn_count"),
                JsonPrimitive("per_kind_route_strict"))
        },
        Mutation("K09_k4_validity_replaced_by_k5",
            "RUN_K4 is INVALID RUN and is never quoted as if it were RUN_K5",
            "VALIDITY_CHANGED: K4") { docs ->
            docs["crossRun"] = mutate(docs["crossRun"]!!, listOf("runs", "K4", "validity"),
                JsonPrimitive("VALID BASELINE"))
        },
        Mutation("K10_k6_authority_replaced",
            "the report whose K6 column is the defect is never an authority",
            "WRONG_VALIDITY_AUTHORITY: K6") { docs ->
            docs["crossRun"] = mutate(docs["crossRun"]!!,
                listOf("runs", "K6", "validity_authority"),
                JsonPrimitive("the K3_K4_K5_K6_COMPARISON.json report"))
        },
        Mutation("K11_measurement_only_marked_as_pass",
            "a value with no authorised bar cannot be a pass",
            "MEASUREMENT_ONLY_MARKED_AS_PASS: ryeong_metrics_on_device") { docs ->
            docs["acceptance"] = mutate(docs["acceptance"]!!,
                listOf("layer_2_performance_acceptance", "ryeong_metrics_on_device", "status"),
                JsonPrimitive("PASS"))
        },
        Mutation("K12_safety_gate_given_a_budget",
            "a zero-tolerance gate with a budget is not a zero-tolerance gate",
            "SAFETY_GATE_NOT_ZERO") { docs ->
            val acceptance = docs["acceptance"]!!
            val layerTwo = acceptance["layer_2_performance_acceptance"] as JsonObject
            val safety = layerTwo["safety_hard_gates"] as JsonObject
            val gates = (safety["gates"] as JsonArray).toMutableList()
            val first = (gates[0] as JsonObject).toMutableMap()
            first["requires"] = JsonPrimitive(2)
            gates[0] = JsonObject(first)
            docs["acceptance"] = mutate(acceptance,
                listOf("layer_2_performance_acceptance", "safety_hard_gates", "gates"),
                JsonArray(gates))
        },
        Mutation("K13_smoke_consumes_the_official_invocation",
            "the one invocation RUN_D7 has, spent by a shakeout",
            "SMOKE_CONSUMES_OFFICIAL_INVOCATION") { docs ->
            docs["separation"] = mutate(docs["separation"]!!,
                listOf("runs", "SMOKE", "consumes_official_invocation"), JsonPrimitive(true))
        },
        Mutation("K14_official_accepts_a_scenario_cap",
            "a five-scenario file wearing the official identifier",
            "OFFICIAL_ACCEPTS_SCENARIO_LIMIT: D7") { docs ->
            docs["separation"] = mutate(docs["separation"]!!,
                listOf("runs", "D7", "scenario_limit_allowed"), JsonPrimitive(true))
        },
        Mutation("K15_smoke_namespace_nested_in_official",
            "a name that is a prefix of another is a directory that can be walked into",
            "NAMESPACES_NOT_DISJOINT") { docs ->
            docs["separation"] = mutate(docs["separation"]!!,
                listOf("runs", "SMOKE", "output_namespace"),
                JsonPrimitive("ryeong_device_eval_v7_official/smoke"))
        },
        Mutation("K16_policy_stops_being_fail_closed",
            "a position nobody declared becomes exempt",
            "POLICY_NOT_FAIL_CLOSED") { docs ->
            docs["policy"] = mutate(docs["policy"]!!, listOf("operational_pointer_prefixes"),
                JsonArray(listOf(JsonPrimitive("/instrumentation"))))
        },
        Mutation("K17_policy_exempts_an_operational_position",
            "the command an operator runs becomes a place a previous version may be named",
            "POLICY_EXEMPTS_AN_OPERATIONAL_POSITION: /instrumentation/command") { docs ->
            val policy = docs["policy"]!!
            val allowed = (policy["allowed_pointer_prefixes"] as JsonArray).toMutableList()
            allowed += JsonPrimitive("/instrumentation")
            docs["policy"] = mutate(policy, listOf("allowed_pointer_prefixes"), JsonArray(allowed))
        },
        Mutation("K18_symlink_stops_being_refused",
            "a declared path that resolves through a symlink is not the file it names",
            "POLICY_SHAPE_MISSING: symlink") { docs ->
            docs["policy"] = dropFromArray(docs["policy"]!!, "refused_path_shapes", "symlink")
        },
    )

    @Test
    fun `the unmutated contracts produce no complaint`() {
        assertEquals(emptyList<String>(), complaints(
            obj(V7Contracts.CROSS_RUN), obj(V7Contracts.ACCEPTANCE),
            obj(V7Contracts.SEPARATION), obj(V7Contracts.PATH_POLICY),
        ))
    }

    @Test
    fun `every mutation produces the complaint it was built to produce`() {
        val missed = mutableListOf<String>()
        mutations.forEach { mutation ->
            val documents = mutableMapOf(
                "crossRun" to obj(V7Contracts.CROSS_RUN),
                "acceptance" to obj(V7Contracts.ACCEPTANCE),
                "separation" to obj(V7Contracts.SEPARATION),
                "policy" to obj(V7Contracts.PATH_POLICY),
            )
            mutation.apply(documents)
            val found = complaints(documents["crossRun"]!!, documents["acceptance"]!!,
                documents["separation"]!!, documents["policy"]!!)
            if (found.none { it.startsWith(mutation.expect) }) {
                missed += "${mutation.id}: expected '${mutation.expect}', got $found"
            }
        }
        assertEquals("mutations the checker did not notice", emptyList<String>(), missed)
    }

    @Test
    fun `the mutation set covers every contract`() {
        assertTrue("too few mutations to mean anything", mutations.size >= 18)
        assertEquals("duplicate mutation ids", mutations.size, mutations.map { it.id }.toSet().size)
    }
}
