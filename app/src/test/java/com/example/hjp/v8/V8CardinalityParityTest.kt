package com.example.hjp.v8

import com.example.hjp.eval.ryeong2.EvidenceRoot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two readers, asked the same questions, compared field by field — and the answer written down.
 *
 * §14. Two implementations that agree is the point. One implementation checked against itself is
 * not, and neither is two implementations that agree because one was written by transliterating the
 * other's control flow. [V8Cardinality] and `cardinality_scanner_v8.py` were written separately
 * against the same policy document; what is compared here is their output, not their shape.
 *
 * The comparison is on all four things that can differ independently:
 *
 *   * the occurrence count      — how many positions
 *   * the target count          — how many distinct wrong things
 *   * the pointer list          — which positions, in which order
 *   * the pointer→target map    — which position names which target
 *
 * A pair of readers that agreed on the two counts and disagreed on the mapping would have agreed on
 * nothing that matters. That is the v7 failure mode one level down: the right totals attached to the
 * wrong meaning.
 *
 * This test also writes KOTLIN_CARDINALITY_REPORT.json and CARDINALITY_PARITY.json, which the
 * contract, the freeze and the Phase B manifest all pin.
 */
class V8CardinalityParityTest {

    private val policyRelative = "tools/ryeong_official_v8/contracts/phase_b_path_policy.json"
    private val pythonRelative =
        "integration_evidence/evaluation/ryeong_official_v8/parity/PYTHON_CARDINALITY_REPORT.json"
    private val outDirRelative = "integration_evidence/evaluation/ryeong_official_v8/parity"

    private val templates = listOf(
        Triple("v6_template", "v6",
            "integration_evidence/evaluation/ryeong_official_v6/phase_b_template/" +
                "device_execution_manifest_template.json"),
        Triple("v7_template", "v7",
            "integration_evidence/evaluation/ryeong_official_v7/phase_b_template/" +
                "device_execution_manifest_template.json"),
        Triple("v8_template", "v8",
            "integration_evidence/evaluation/ryeong_official_v8/phase_b_template/" +
                "device_execution_manifest_template.json"),
    )

    private fun policy(): JsonObject =
        V8Cardinality.json.parseToJsonElement(
            EvidenceRoot.file(policyRelative).readText(Charsets.UTF_8)) as JsonObject

    private fun python(): JsonObject? {
        val target = EvidenceRoot.file(pythonRelative)
        if (!target.isFile) return null
        return V8Cardinality.json.parseToJsonElement(target.readText(Charsets.UTF_8)) as JsonObject
    }

    private fun stringOf(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.let { if (it.isString) it.contentOrNull else null }

    private fun intOf(element: JsonElement?): Int? = (element as? JsonPrimitive)?.intOrNull

    private fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { stringOf(it) }.orEmpty()

    private fun quote(text: String): String =
        buildString {
            append('"')
            text.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character < ' ') append("\\u%04x".format(character.code))
                            else append(character)
                }
            }
            append('"')
        }

    private fun renderReport(key: String, report: V8Cardinality.Report): String = buildString {
        append("    ${quote(key)}: {\n")
        append("      \"target\": ${quote(report.target)},\n")
        append("      \"as_version\": ${quote(report.asVersion)},\n")
        append("      \"target_sha256\": ${report.targetSha256?.let { quote(it) } ?: "null"},\n")
        append("      \"operational_occurrence_count\": ${report.operationalOccurrenceCount},\n")
        append("      \"operational_occurrence_pointers\": [")
        append(report.operationalOccurrencePointers.joinToString(", ") { quote(it) })
        append("],\n")
        append("      \"distinct_invalid_target_count\": ${report.distinctInvalidTargetCount},\n")
        append("      \"distinct_invalid_targets\": [")
        append(report.distinctInvalidTargets.joinToString(", ") { quote(it) })
        append("],\n")
        append("      \"pointer_to_target\": {")
        append(report.pointerToTarget.entries.joinToString(", ") {
            "${quote(it.key)}: ${quote(it.value)}"
        })
        append("},\n")
        append("      \"target_to_pointers\": {")
        append(report.targetToPointers.entries.joinToString(", ") { (target, pointers) ->
            "${quote(target)}: [${pointers.joinToString(", ") { quote(it) }}]"
        })
        append("},\n")
        append("      \"checked_strings\": ${report.checkedStrings},\n")
        append("      \"allowed_position_strings\": ${report.allowedPositionStrings},\n")
        append("      \"pointer_list_has_no_duplicates\": ${report.pointerListHasNoDuplicates},\n")
        append("      \"occurrence_detail\": [\n")
        append(report.occurrences.joinToString(",\n") { row ->
            "        {\"json_pointer\": ${quote(row.jsonPointer)}, " +
                "\"position_classification\": ${quote(row.positionClassification)}, " +
                "\"detected_token\": ${quote(row.detectedToken)}, " +
                "\"normalized_target\": ${quote(row.normalizedTarget)}, " +
                "\"violation_code\": ${quote(row.violationCode)}, " +
                "\"source_value\": ${quote(row.sourceValue)}, " +
                "\"why_operational\": ${quote(row.whyOperational)}}"
        })
        append("\n      ]\n")
        append("    }")
    }

    @Test
    fun `the kotlin reader derives its counts by walking the document`() {
        val rows = V8Cardinality.selfCheck(policy())
        rows.forEach { row ->
            assertTrue(
                "self check ${row.case} disagrees: occurrences " +
                    "${row.observedOccurrences}/${row.expectedOccurrences}, targets " +
                    "${row.observedTargets}/${row.expectedTargets}",
                row.agrees,
            )
        }
        assertEquals("the self check must cover all three shapes", 3, rows.size)
    }

    @Test
    fun `the kotlin reader finds five occurrences and three targets in the v6 template`() {
        val report = V8Cardinality.scanFile(templates[0].third, policy(), "v6")
        assertNotNull("the v6 template is not in the tree", report)
        assertEquals("occurrence count", 5, report!!.operationalOccurrenceCount)
        assertEquals("distinct target count", 3, report.distinctInvalidTargetCount)
        assertEquals(
            "the two counts must not be equal for this template, or the case proves nothing",
            true, report.operationalOccurrenceCount != report.distinctInvalidTargetCount,
        )
        assertEquals(
            listOf("DEVICE_RECOMPUTATION.json", "DEVICE_SCORE.json", "v5"),
            report.distinctInvalidTargets,
        )
        assertEquals(
            "DEVICE_SCORE.json must be reached from two pointers",
            2, report.targetToPointers["DEVICE_SCORE.json"]?.size,
        )
        assertEquals(
            "DEVICE_RECOMPUTATION.json must be reached from two pointers",
            2, report.targetToPointers["DEVICE_RECOMPUTATION.json"]?.size,
        )
        assertEquals(
            "the v5 namespace must be reached from one pointer",
            1, report.targetToPointers["v5"]?.size,
        )
    }

    @Test
    fun `the v7 and v8 templates are clean under both units`() {
        listOf(templates[1], templates[2]).forEach { (key, version, relative) ->
            val report = V8Cardinality.scanFile(relative, policy(), version)
            assertNotNull("$key is not in the tree", report)
            assertEquals("$key has an operational occurrence: ${report!!.occurrences}",
                0, report.operationalOccurrenceCount)
            assertEquals("$key has a distinct invalid target",
                0, report.distinctInvalidTargetCount)
        }
    }

    @Test
    fun `the python reader and the kotlin reader agree, and the agreement is written down`() {
        val policyDocument = policy()
        val reports = templates.mapNotNull { (key, version, relative) ->
            V8Cardinality.scanFile(relative, policyDocument, version)?.let { key to it }
        }
        assertEquals("every template must be readable", templates.size, reports.size)

        val kotlinReport = buildString {
            append("{\n")
            append("  \"schema\": \"ryeong_v8_cardinality_report/v1\",\n")
            append("  \"reader\": \"kotlin\",\n")
            append("  \"reader_version\": ${quote(V8Cardinality.READER_VERSION)},\n")
            append("  \"reader_path\": ${quote("app/src/test/java/com/example/hjp/v8/V8Cardinality.kt")},\n")
            append("  \"reader_sha256\": ")
            append(V8Cardinality.sha256("app/src/test/java/com/example/hjp/v8/V8Cardinality.kt")
                ?.let { quote(it) } ?: "null")
            append(",\n")
            append("  \"policy_path\": ${quote(policyRelative)},\n")
            append("  \"policy_sha256\": ")
            append(V8Cardinality.sha256(policyRelative)?.let { quote(it) } ?: "null")
            append(",\n")
            append("  \"policy_version\": ")
            append(stringOf(policyDocument["version"])?.let { quote(it) } ?: "null")
            append(",\n")
            append("  \"self_check_passed\": ")
            append(V8Cardinality.selfCheck(policyDocument).all { it.agrees })
            append(",\n")
            append("  \"templates\": {\n")
            append(reports.joinToString(",\n") { (key, report) -> renderReport(key, report) })
            append("\n  }\n")
            append("}\n")
        }
        EvidenceRoot.dir(outDirRelative)
            .resolve("KOTLIN_CARDINALITY_REPORT.json")
            .writeText(kotlinReport, Charsets.UTF_8)

        val pythonDocument = python()
        assertNotNull(
            "the python cardinality report is not in the tree; run " +
                "tools/ryeong_official_v8/cardinality_scanner_v8.py first: $pythonRelative",
            pythonDocument,
        )

        val disagreements = mutableListOf<String>()
        reports.forEach { (key, report) ->
            val block = pythonDocument!![key] as? JsonObject
            if (block == null) {
                disagreements += "$key: absent from the python report"
                return@forEach
            }
            if (intOf(block["operational_occurrence_count"]) != report.operationalOccurrenceCount) {
                disagreements += "$key: occurrence count kotlin=" +
                    "${report.operationalOccurrenceCount} python=" +
                    "${intOf(block["operational_occurrence_count"])}"
            }
            if (intOf(block["distinct_invalid_target_count"]) != report.distinctInvalidTargetCount) {
                disagreements += "$key: target count kotlin=" +
                    "${report.distinctInvalidTargetCount} python=" +
                    "${intOf(block["distinct_invalid_target_count"])}"
            }
            val pythonPointers = strings(block["operational_occurrence_pointers"])
            if (pythonPointers != report.operationalOccurrencePointers) {
                disagreements += "$key: pointer list order or membership differs; " +
                    "kotlin=${report.operationalOccurrencePointers} python=$pythonPointers"
            }
            val pythonTargets = strings(block["distinct_invalid_targets"])
            if (pythonTargets.sorted() != report.distinctInvalidTargets) {
                disagreements += "$key: target set differs; " +
                    "kotlin=${report.distinctInvalidTargets} python=$pythonTargets"
            }
            val pythonMapping = (block["pointer_to_target"] as? JsonObject)
                ?.mapValues { stringOf(it.value) }.orEmpty()
            if (pythonMapping != report.pointerToTarget) {
                disagreements += "$key: pointer-to-target mapping differs; " +
                    "kotlin=${report.pointerToTarget} python=$pythonMapping"
            }
        }

        val parity = buildString {
            append("{\n")
            append("  \"schema\": \"ryeong_v8_cardinality_parity/v1\",\n")
            append("  \"why\": \"two implementations written separately against the same policy. ")
            append("Comparing only the two counts would miss the failure mode one level down: the ")
            append("right totals attached to the wrong meaning. The pointer list and the ")
            append("pointer-to-target mapping are compared too.\",\n")
            append("  \"python_report\": ${quote(pythonRelative)},\n")
            append("  \"kotlin_report\": ${quote("$outDirRelative/KOTLIN_CARDINALITY_REPORT.json")},\n")
            append("  \"compared\": [\"operational_occurrence_count\", ")
            append("\"distinct_invalid_target_count\", \"operational_occurrence_pointers\", ")
            append("\"distinct_invalid_targets\", \"pointer_to_target\"],\n")
            append("  \"templates_compared\": [")
            append(reports.joinToString(", ") { quote(it.first) })
            append("],\n")
            append("  \"disagreements\": [")
            append(disagreements.joinToString(", ") { quote(it) })
            append("],\n")
            append("  \"readers_agree\": ${disagreements.isEmpty()},\n")
            append("  \"verdict\": ")
            append(quote(if (disagreements.isEmpty()) "CARDINALITY PARITY" else "CARDINALITY DISAGREEMENT"))
            append("\n}\n")
        }
        EvidenceRoot.dir(outDirRelative).resolve("CARDINALITY_PARITY.json")
            .writeText(parity, Charsets.UTF_8)

        assertEquals("the two readers disagree: $disagreements", emptyList<String>(), disagreements)
    }
}
