package com.example.hjp.v8char

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
 * What the v8 characterization needs, and nothing it decides.
 *
 * Everything here reads the tree as it is: the frozen v6 template, the frozen v7 case table and
 * policy, the v8 contract once it exists. Nothing writes, and nothing holds a copy of a value it is
 * supposed to be checking — a fixture that remembers "5" would pass a scanner that had forgotten
 * how to count.
 *
 * The scan below is this file's own derivation of the policy, written from the policy document
 * rather than borrowed from either reader. That is deliberate: the characterization has to be able
 * to disagree with both implementations, or it is only checking that they agree with each other.
 */
object V8CharacterizationFixture {

    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    const val V6_TEMPLATE =
        "integration_evidence/evaluation/ryeong_official_v6/phase_b_template/" +
            "device_execution_manifest_template.json"
    const val V7_TEMPLATE =
        "integration_evidence/evaluation/ryeong_official_v7/phase_b_template/" +
            "device_execution_manifest_template.json"
    const val V8_TEMPLATE =
        "integration_evidence/evaluation/ryeong_official_v8/phase_b_template/" +
            "device_execution_manifest_template.json"

    const val V7_PATH_POLICY = "tools/ryeong_official_v7/contracts/phase_b_path_policy.json"
    const val V8_PATH_POLICY = "tools/ryeong_official_v8/contracts/phase_b_path_policy.json"
    const val V7_CASE_TABLE = "tools/ryeong_official_v7/contracts/characterization_cases_v7.json"
    const val V8_CASE_TABLE = "tools/ryeong_official_v8/contracts/characterization_cases_v8.json"

    const val CARDINALITY_CONTRACT =
        "tools/ryeong_official_v8/contracts/phase_b_cardinality_contract.json"
    const val HISTORICAL_FAILURE_CONTRACT =
        "tools/ryeong_official_v8/contracts/historical_failure_contract.json"

    const val PYTHON_CARDINALITY_REPORT =
        "integration_evidence/evaluation/ryeong_official_v8/parity/PYTHON_CARDINALITY_REPORT.json"

    const val PYTHON_READER = "tools/ryeong_official_v8/cardinality_scanner_v8.py"
    const val KOTLIN_READER = "app/src/test/java/com/example/hjp/v8/V8Cardinality.kt"

    const val V7_WITNESS =
        "integration_evidence/evaluation/ryeong_official_v8/historical_failure/" +
            "v7_characterization_oracle/V7_HISTORICAL_FAILURE_WITNESS.json"

    /** The v7 case whose expectation is the defect v8 exists to separate. */
    const val FROZEN_V7_CASE_ID = "B30_v6_template_reproduces_the_defect"

    fun file(relative: String): File = EvidenceRoot.file(relative)

    fun present(relative: String): Boolean = file(relative).isFile

    fun requireObject(relative: String): JsonObject {
        val target = file(relative)
        require(target.isFile) { "characterization input is not in the tree: $relative" }
        return json.parseToJsonElement(target.readText(Charsets.UTF_8)) as JsonObject
    }

    fun objectOrNull(relative: String): JsonObject? {
        val target = file(relative)
        if (!target.isFile) return null
        return json.parseToJsonElement(target.readText(Charsets.UTF_8)) as? JsonObject
    }

    fun sha256(relative: String): String? {
        val target = file(relative)
        if (!target.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun stringOf(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.let { if (it.isString) it.contentOrNull else null }

    fun intOf(element: JsonElement?): Int? = (element as? JsonPrimitive)?.intOrNull

    fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { stringOf(it) }.orEmpty()

    /** Every string value in a document, as (json pointer, value), in document order. */
    fun everyString(root: JsonElement): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        fun walk(element: JsonElement, pointer: String) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) -> walk(value, "$pointer/$key") }
                is JsonArray ->
                    element.forEachIndexed { index, value -> walk(value, "$pointer/$index") }
                is JsonPrimitive -> stringOf(element)?.let { found += pointer to it }
                else -> Unit
            }
        }
        walk(root, "")
        return found
    }

    // ----------------------------------------------------------------------------------------------
    // One occurrence, with everything needed to defend it.
    // ----------------------------------------------------------------------------------------------

    data class Occurrence(
        val pointer: String,
        val positionClassification: String,
        val detectedToken: String,
        val normalizedTarget: String,
        val violationCode: String,
        val sourceValue: String,
        val whyOperational: String,
    )

    /**
     * A version token matched as a token: both boundaries non-alphanumeric. `rev5` in a build
     * fingerprint is not a v5 reference; `_v5_`, `/v5/` and `-v5.` are.
     */
    fun mentionsVersion(text: String, token: String): Boolean {
        val lower = text.lowercase()
        val needle = token.lowercase()
        var index = lower.indexOf(needle)
        while (index >= 0) {
            val before = if (index == 0) ' ' else lower[index - 1]
            val afterIndex = index + needle.length
            val after = if (afterIndex >= lower.length) ' ' else lower[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            index = lower.indexOf(needle, index + 1)
        }
        return false
    }

    private val artifactName = Regex("[A-Za-z0-9_.\\-]+\\.(json|jsonl|py|kt|apk|md|txt|log)")

    /**
     * Scan one template under one policy, as the version it claims to be.
     *
     * Returns occurrences in document order, one per (pointer, code, target) triple. Two different
     * pointers naming the same target are two occurrences, deliberately: collapsing them here is
     * precisely the step that turned five into three.
     */
    fun scan(template: JsonObject, policy: JsonObject, currentVersion: String): List<Occurrence> {
        val allowed = strings(policy["allowed_pointer_prefixes"])
        val operationalPrefixes = strings(policy["operational_pointer_prefixes"])
        val previous = strings(policy["previous_version_tokens"])
            .filterNot { it.equals(currentVersion, ignoreCase = true) }
        val declared = strings(policy["current_version_artifacts"]).toSet()
        val retired = (policy["previous_version_artifacts"] as? JsonObject)
            ?.mapValues { stringOf(it.value).orEmpty() }.orEmpty()

        val found = mutableListOf<Occurrence>()
        everyString(template).forEach { (pointer, text) ->
            if (allowed.any { pointer == it || pointer.startsWith("$it/") }) return@forEach
            val operational = operationalPrefixes.isEmpty() ||
                operationalPrefixes.any { pointer == it || pointer.startsWith("$it/") }
            if (!operational) return@forEach
            val whyOperational = if (operationalPrefixes.isEmpty()) {
                "fail-closed default: the policy declares no operational prefix list, so every " +
                    "position that is not explicitly allowed is operational"
            } else {
                "the pointer falls under a declared operational prefix"
            }
            previous.forEach { token ->
                if (mentionsVersion(text, token)) {
                    found += Occurrence(
                        pointer = pointer,
                        positionClassification = "OPERATIONAL",
                        detectedToken = token,
                        normalizedTarget = token,
                        violationCode = "PREVIOUS_VERSION_IN_OPERATIONAL_POSITION",
                        sourceValue = text,
                        whyOperational = whyOperational,
                    )
                }
            }
            retired.forEach { (name, _) ->
                if (text.contains(name)) {
                    found += Occurrence(
                        pointer = pointer,
                        positionClassification = "OPERATIONAL",
                        detectedToken = name,
                        normalizedTarget = name,
                        violationCode = "UNDECLARED_ARTIFACT_REFERENCE",
                        sourceValue = text,
                        whyOperational = whyOperational,
                    )
                }
            }
            artifactName.findAll(text).map { it.value }
                .filter { it !in declared && it !in retired }
                .forEach { name ->
                    found += Occurrence(
                        pointer = pointer,
                        positionClassification = "OPERATIONAL",
                        detectedToken = name,
                        normalizedTarget = name,
                        violationCode = "UNDECLARED_ARTIFACT_REFERENCE",
                        sourceValue = text,
                        whyOperational = whyOperational,
                    )
                }
        }
        return found.distinctBy { Triple(it.pointer, it.violationCode, it.normalizedTarget) }
    }

    /** Occurrences, in the contract's declared order. One per offending position. */
    fun occurrencePointers(occurrences: List<Occurrence>): List<String> =
        occurrences.map { it.pointer }

    /** Targets, deduplicated by normalised target and by nothing else. */
    fun distinctTargets(occurrences: List<Occurrence>): List<String> =
        occurrences.map { it.normalizedTarget }.distinct().sorted()

    fun pointerToTarget(occurrences: List<Occurrence>): Map<String, String> =
        occurrences.associate { it.pointer to it.normalizedTarget }

    /** The policy the v8 scan uses: the v8 copy when present, otherwise the frozen v7 original. */
    fun activePathPolicy(): JsonObject =
        objectOrNull(V8_PATH_POLICY) ?: requireObject(V7_PATH_POLICY)

    fun activePathPolicyRelative(): String =
        if (present(V8_PATH_POLICY)) V8_PATH_POLICY else V7_PATH_POLICY
}
