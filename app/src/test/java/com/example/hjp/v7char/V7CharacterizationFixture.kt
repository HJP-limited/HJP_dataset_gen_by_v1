package com.example.hjp.v7char

import com.example.hjp.eval.ryeong2.EvidenceRoot
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What both v7 characterization tests need, and nothing either of them decides.
 *
 * The two defects are read from the tree as it is: the real RUN_K6 record, the real v6 Phase B
 * template, the v7 contracts once they exist. Nothing here writes, and nothing here holds a copy of
 * a value it is supposed to be checking.
 */
object V7CharacterizationFixture {

    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    const val K6_RESULT =
        "integration_evidence/evaluation/ryeong_official_v6/result/jvm_keyword/result.json"
    const val K6_HOST_VALIDITY =
        "integration_evidence/evaluation/ryeong_official_v6/result/jvm_keyword/host_validity.json"
    const val K6_EXISTING_COMPARISON =
        "integration_evidence/evaluation/ryeong_official_v6/comparison/K3_K4_K5_K6_COMPARISON.json"
    const val K3_RESULT =
        "integration_evidence/evaluation/ryeong_official_v3/result/jvm_keyword/result.json"

    const val V6_TEMPLATE =
        "integration_evidence/evaluation/ryeong_official_v6/phase_b_template/" +
            "device_execution_manifest_template.json"
    const val V7_TEMPLATE =
        "integration_evidence/evaluation/ryeong_official_v7/phase_b_template/" +
            "device_execution_manifest_template.json"
    const val V7_COMMANDS =
        "integration_evidence/evaluation/ryeong_official_v7/phase_b_template/PHASE_B_COMMANDS.md"

    const val COMPARISON_CONTRACT =
        "tools/ryeong_official_v7/contracts/cross_run_comparison_contract.json"
    const val PATH_POLICY =
        "tools/ryeong_official_v7/contracts/phase_b_path_policy.json"

    fun file(relative: String): File = EvidenceRoot.file(relative)

    fun requireObject(relative: String): JsonObject {
        val target = file(relative)
        require(target.isFile) { "characterization input is not in the tree: $relative" }
        return json.parseToJsonElement(target.readText(Charsets.UTF_8)) as JsonObject
    }

    /** `$.a.b.c` against a parsed record. `$` is the record itself. Absent is null, not an error. */
    fun at(root: JsonObject, path: String): JsonElement? {
        if (path == "$") return root
        var current: JsonElement = root
        path.removePrefix("$.").split('.').forEach { segment ->
            val here = current as? JsonObject ?: return null
            current = here[segment] ?: return null
        }
        return current
    }

    fun intOf(element: JsonElement?): Int? = (element as? JsonPrimitive)?.intOrNull

    fun stringOf(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.let { if (it.isString) it.contentOrNull else null }

    fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { stringOf(it) }.orEmpty()

    /** Every `<...>` placeholder hint in a template, as (json pointer, hint). */
    fun placeholderHints(root: JsonObject): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        fun walk(element: JsonElement, pointer: String) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) -> walk(value, "$pointer/$key") }
                is JsonArray -> element.forEachIndexed { index, value -> walk(value, "$pointer/$index") }
                is JsonPrimitive -> {
                    val text = stringOf(element) ?: return
                    if (text.startsWith("<") && text.endsWith(">")) found += pointer to text
                }
                else -> Unit
            }
        }
        walk(root, "")
        return found
    }

    /** Every string value in a document, as (json pointer, value). */
    fun everyString(root: JsonElement): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        fun walk(element: JsonElement, pointer: String) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) -> walk(value, "$pointer/$key") }
                is JsonArray -> element.forEachIndexed { index, value -> walk(value, "$pointer/$index") }
                is JsonPrimitive -> stringOf(element)?.let { found += pointer to it }
                else -> Unit
            }
        }
        walk(root, "")
        return found
    }
}
