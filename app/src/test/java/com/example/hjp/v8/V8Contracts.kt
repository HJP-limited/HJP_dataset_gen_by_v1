package com.example.hjp.v8

import com.example.hjp.eval.ryeong2.EvidenceRoot
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Paths and readers the v8 contract, mutation and provenance suites share.
 *
 * Nothing here decides anything. It is the list of files v8 is made of, and the small readers needed
 * to open them, kept in one place so that three suites cannot drift into disagreeing about where
 * something lives.
 */
object V8Contracts {

    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    const val CARDINALITY = "tools/ryeong_official_v8/contracts/phase_b_cardinality_contract.json"
    const val HISTORICAL_FAILURE =
        "tools/ryeong_official_v8/contracts/historical_failure_contract.json"
    const val HISTORICAL_FREEZE_POLICY =
        "tools/ryeong_official_v8/contracts/historical_freeze_policy.json"
    const val PATH_POLICY = "tools/ryeong_official_v8/contracts/phase_b_path_policy.json"
    const val RUN_SEPARATION = "tools/ryeong_official_v8/contracts/run_separation_contract.json"
    const val ACCEPTANCE = "tools/ryeong_official_v8/contracts/device_acceptance_contract.json"
    const val METRIC_SCORING = "tools/ryeong_official_v8/contracts/metric_scoring_contract.json"
    const val CONTACT_DEPENDENCY =
        "tools/ryeong_official_v8/contracts/contact_dependency_contract.json"
    const val CASE_TABLE = "tools/ryeong_official_v8/contracts/characterization_cases_v8.json"

    const val V7_ACCEPTANCE = "tools/ryeong_official_v7/contracts/device_acceptance_contract.json"
    const val V7_METRIC_SCORING = "tools/ryeong_official_v7/contracts/metric_scoring_contract.json"
    const val V7_CONTACT_DEPENDENCY =
        "tools/ryeong_official_v7/contracts/contact_dependency_contract.json"
    const val V7_PATH_POLICY = "tools/ryeong_official_v7/contracts/phase_b_path_policy.json"
    const val V7_CASE_TABLE = "tools/ryeong_official_v7/contracts/characterization_cases_v7.json"

    const val WITNESS =
        "integration_evidence/evaluation/ryeong_official_v8/historical_failure/" +
            "v7_characterization_oracle/V7_HISTORICAL_FAILURE_WITNESS.json"
    const val PYTHON_CARDINALITY =
        "integration_evidence/evaluation/ryeong_official_v8/parity/PYTHON_CARDINALITY_REPORT.json"
    const val CARDINALITY_PARITY =
        "integration_evidence/evaluation/ryeong_official_v8/parity/CARDINALITY_PARITY.json"
    const val MUTATION_REPORT =
        "integration_evidence/evaluation/ryeong_official_v8/mutation/MUTATION_REPORT.json"
    const val READER_PROVENANCE =
        "integration_evidence/evaluation/ryeong_official_v8/provenance/READER_PROVENANCE.json"
    const val HOST_RUNNER_RETARGET =
        "integration_evidence/evaluation/ryeong_official_v8/provenance/HOST_RUNNER_RETARGET.json"
    const val DEVICE_RUNNER_RETARGET =
        "integration_evidence/evaluation/ryeong_official_v8/provenance/DEVICE_RUNNER_RETARGET.json"
    const val PATH_POLICY_RETARGET =
        "integration_evidence/evaluation/ryeong_official_v8/provenance/PATH_POLICY_RETARGET.json"
    const val TEMPLATE_RETARGET =
        "integration_evidence/evaluation/ryeong_official_v8/provenance/PHASE_B_TEMPLATE_RETARGET.json"
    const val CONTRACT_RETARGET =
        "integration_evidence/evaluation/ryeong_official_v8/provenance/CONTRACT_RETARGET.json"
    const val ENVIRONMENT_PROVENANCE =
        "integration_evidence/evaluation/ryeong_official_v8/baseline/ENVIRONMENT_PROVENANCE.json"

    const val V8_TEMPLATE =
        "integration_evidence/evaluation/ryeong_official_v8/phase_b_template/" +
            "device_execution_manifest_template.json"
    const val V8_COMMANDS =
        "integration_evidence/evaluation/ryeong_official_v8/phase_b_template/PHASE_B_COMMANDS.md"

    /** The frozen v7 characterization suite. It fails, on purpose, throughout v8. */
    const val V7_FAILING_SOURCE =
        "app/src/test/java/com/example/hjp/v7char/V7PhaseBVersionPathCharacterizationTest.kt"
    const val V7_FAILING_FIXTURE =
        "app/src/test/java/com/example/hjp/v7char/V7CharacterizationFixture.kt"

    /** Production source v8 must not have touched. */
    val PRODUCTION_ROOTS = listOf(
        "agent-contract/src/main", "agent-core/src/main", "search-core/src/main",
        "tool-contract/src/main", "tool-contact/src/main", "tool-android-intents/src/main",
        "tool-datetime/src/main", "llm-litert/src/main", "app/src/main/java",
    )

    fun file(relative: String): File = EvidenceRoot.file(relative)

    fun present(relative: String): Boolean = file(relative).isFile

    fun obj(relative: String): JsonObject? {
        val target = file(relative)
        if (!target.isFile) return null
        return json.parseToJsonElement(target.readText(Charsets.UTF_8)) as? JsonObject
    }

    fun requireObj(relative: String): JsonObject =
        obj(relative) ?: error("required contract is not in the tree: $relative")

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

    fun str(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.let { if (it.isString) it.contentOrNull else null }

    fun int(element: JsonElement?): Int? = (element as? JsonPrimitive)?.intOrNull

    fun bool(element: JsonElement?): Boolean? = (element as? JsonPrimitive)?.booleanOrNull

    fun list(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { str(it) }.orEmpty()

    fun at(root: JsonObject, path: String): JsonElement? {
        var current: JsonElement = root
        path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            val here = current as? JsonObject ?: return null
            current = here[segment] ?: return null
        }
        return current
    }

    /** Every string in a document, so a suite can ask "does this appear anywhere". */
    fun everyString(root: JsonElement): List<String> {
        val found = mutableListOf<String>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) -> found += key; walk(value) }
                is JsonArray -> element.forEach { walk(it) }
                is JsonPrimitive -> str(element)?.let { found += it }
                else -> Unit
            }
        }
        walk(root)
        return found
    }
}
