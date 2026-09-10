package com.example.hjp.v9

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
 * Paths and readers the v9 contract, mutation, parity and provenance suites share.
 *
 * Nothing here decides anything. It is the list of files v9 is made of, kept in one place so four
 * suites cannot drift into disagreeing about where something lives — which is, at one remove, the
 * defect this whole version exists to correct.
 */
object V9Contracts {

    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    // ---- v9's own -----------------------------------------------------------------------------
    const val REGISTRY = "tools/ryeong_official_v9/contracts/run_identity_registry.json"
    const val CROSS_RUN = "tools/ryeong_official_v9/contracts/cross_run_comparison_contract.json"
    const val EVIDENCE_PATHS = "tools/ryeong_official_v9/contracts/evidence_path_policy.json"
    const val FREEZE_POLICY = "tools/ryeong_official_v9/contracts/historical_freeze_policy.json"
    const val ACCEPTANCE = "tools/ryeong_official_v9/contracts/device_acceptance_contract.json"
    const val RUN_SEPARATION = "tools/ryeong_official_v9/contracts/run_separation_contract.json"
    const val PATH_POLICY = "tools/ryeong_official_v9/contracts/phase_b_path_policy.json"
    const val CASE_TABLE = "tools/ryeong_official_v9/contracts/characterization_cases_v9.json"
    const val HISTORICAL_FAILURE =
        "tools/ryeong_official_v9/contracts/historical_failure_contract.json"
    const val CARDINALITY = "tools/ryeong_official_v9/contracts/phase_b_cardinality_contract.json"

    const val GENERATED_IDENTITY = "tools/ryeong_official_v9/generated/V9RunIdentity.kt"
    const val SOURCE_IDENTITY = "app/src/test/java/com/example/hjp/v9/V9RunIdentity.kt"

    private const val V9 = "integration_evidence/evaluation/ryeong_official_v9"
    const val PARITY = "$V9/parity/REGISTRY_PARITY.json"
    const val RUNNER_IDENTITY = "$V9/registry/RUNNER_IDENTITY.json"
    const val REGISTRY_BUILD = "$V9/registry/REGISTRY_BUILD.json"
    const val CONTRACT_GENERATION = "$V9/registry/CONTRACT_GENERATION.json"
    const val KOTLIN_GENERATION = "$V9/registry/KOTLIN_IDENTITY_GENERATION.json"
    const val DRIFT_WITNESS =
        "$V9/historical_failure/v8_cross_run_schema_drift/V8_SCHEMA_DRIFT_WITNESS.json"
    const val K8_DIAGNOSTIC = "$V9/diagnostic/k8_read_with_v9_candidate/K8_READ_WITH_V9.json"
    const val MUTATION = "$V9/mutation/MUTATION_REPORT.json"
    const val READER_PROVENANCE = "$V9/provenance/READER_PROVENANCE.json"
    const val DIGEST_RECONCILIATION = "$V9/reconciliation/BASELINE_DIGEST_RECONCILIATION.json"
    const val ZIP_SCOPE = "$V9/reconciliation/ZIP_SCOPE_RECONCILIATION.json"
    const val APFS_AUDIT = "$V9/reconciliation/APFS_COLLISION_AUDIT.json"
    const val V8_FREEZE_DRIFT = "$V9/reconciliation/V8_FREEZE_DRIFT_FINDING.json"
    const val ENV_PROVENANCE = "$V9/baseline/ENVIRONMENT_PROVENANCE.json"
    const val PATH_COLLISION = "$V9/baseline/PATH_COLLISION_AUDIT.json"
    const val ACCEPTANCE_DIFF = "$V9/contract/ACCEPTANCE_SEMANTIC_DIFF.json"

    // ---- frozen, and read-only ------------------------------------------------------------------
    const val V8_CROSS_RUN = "tools/ryeong_official_v8/contracts/cross_run_comparison_contract.json"
    const val V8_BLOCKER =
        "integration_evidence/evaluation/ryeong_official_v8/report/POST_RUN_BLOCKER.json"
    const val V8_VERDICT =
        "integration_evidence/evaluation/ryeong_official_v8/report/V8_VERDICT.json"
    const val K8_RESULT =
        "integration_evidence/evaluation/ryeong_official_v8/result/jvm_keyword/result.json"
    const val K8_STATUS =
        "integration_evidence/evaluation/ryeong_official_v8/result/jvm_keyword/run_status.json"
    const val K8_HOST_VALIDITY =
        "integration_evidence/evaluation/ryeong_official_v8/result/jvm_keyword/host_validity.json"
    const val V7_FAILING_SOURCE =
        "app/src/test/java/com/example/hjp/v7char/V7PhaseBVersionPathCharacterizationTest.kt"

    /** Production source v9 must not have touched. */
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
        obj(relative) ?: error("required v9 artefact is not in the tree: $relative")

    fun text(relative: String): String? {
        val target = file(relative)
        return if (target.isFile) target.readText(Charsets.UTF_8) else null
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

    fun str(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.let { if (it.isString) it.contentOrNull else null }

    fun int(element: JsonElement?): Int? = (element as? JsonPrimitive)?.intOrNull

    fun bool(element: JsonElement?): Boolean? = (element as? JsonPrimitive)?.booleanOrNull

    fun list(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { str(it) }.orEmpty()

    fun at(root: JsonObject?, path: String): JsonElement? {
        var current: JsonElement = root ?: return null
        path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            val here = current as? JsonObject ?: return null
            current = here[segment] ?: return null
        }
        return current
    }

    fun registryRuns(): Map<String, JsonObject> =
        (obj(REGISTRY)?.get("runs") as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap().orEmpty()

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
