package com.example.hjp.v10

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
 * Where the v10 artefacts live, in one place, so five suites cannot disagree about it.
 *
 * That is not a stylistic preference here. The defect v10 exists to remove is several places each
 * holding their own copy of something that has to agree, so a file whose whole job is to be the
 * single place is the smallest version of the fix.
 */
object V10Contracts {

    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private const val V10 = "integration_evidence/evaluation/ryeong_official_v10"

    // ---- contracts, frozen before RUN_K10 ----------------------------------------------------------
    const val REGISTRY = "tools/ryeong_official_v10/contracts/run_registry.json"
    const val CONSUMER_CONTRACT = "tools/ryeong_official_v10/contracts/run_set_consumer_contract.json"
    const val LIFECYCLE_CONTRACT =
        "tools/ryeong_official_v10/contracts/evidence_lifecycle_contract.json"
    const val CROSS_RUN = "tools/ryeong_official_v10/contracts/cross_run_comparison_contract.json"
    const val ACCEPTANCE = "tools/ryeong_official_v10/contracts/device_acceptance_contract.json"
    const val CASE_TABLE = "tools/ryeong_official_v10/contracts/characterization_cases_v10.json"
    const val HISTORICAL_FAILURE =
        "tools/ryeong_official_v10/contracts/historical_failure_contract.json"
    const val METRIC_CONTRACT = "tools/ryeong_official_v10/contracts/metric_scoring_contract.json"

    const val GENERATED_IDENTITY = "tools/ryeong_official_v10/generated/V10RunIdentity.kt"
    const val SOURCE_IDENTITY = "app/src/test/java/com/example/hjp/v10/V10RunIdentity.kt"

    // ---- generated and derived -----------------------------------------------------------------------
    const val PROJECTIONS = "$V10/registry/PROJECTIONS.json"
    const val REGISTRY_BUILD = "$V10/registry/REGISTRY_BUILD.json"
    const val CONTRACT_GENERATION = "$V10/registry/CONTRACT_GENERATION.json"
    const val KOTLIN_GENERATION = "$V10/registry/KOTLIN_IDENTITY_GENERATION.json"
    const val KOTLIN_PARITY = "$V10/registry/KOTLIN_PROJECTION_PARITY.json"
    const val CONSUMER_INVENTORY = "$V10/registry/CONSUMER_INVENTORY.json"
    const val CONSUMER_RUN_SETS = "$V10/registry/CONSUMER_RUN_SET_VALIDATION.json"
    const val ENUMERATION_SCAN = "$V10/registry/RUN_ENUMERATION_SCAN.json"
    const val LIFECYCLE_PATHS = "$V10/lifecycle/LIFECYCLE_PATHS_pre_k10.json"
    const val DRY_RUN = "$V10/dryrun/POST_PROCESSING_DRYRUN.json"
    const val MUTATION = "$V10/mutation/MUTATION_REPORT.json"
    const val PROVENANCE = "$V10/provenance/READER_PROVENANCE.json"
    const val PRE_K10_COMPARISON = "$V10/comparison_pre_k10/K3_TO_K9_PRE_K10_COMPARISON.json"
    const val PRE_K10_VALIDATION = "$V10/comparison_pre_k10/PRE_K10_COMPARISON_VALIDATION.json"
    const val K9_DIAGNOSTIC = "$V10/diagnostic/k9_read_with_v10_candidate/K9_READ_WITH_V10.json"

    // ---- witnesses over the frozen past ---------------------------------------------------------------
    const val RUN_SET_WITNESS =
        "$V10/historical_failure/v9_run_set_enumeration/V9_RUN_SET_WITNESS.json"
    const val DEVIATION_INDEX =
        "$V10/historical_failure/v9_post_freeze_deviations/V9_DEVIATION_INDEX.json"

    // ---- frozen, read-only -----------------------------------------------------------------------------
    const val V9_REGISTRY = "tools/ryeong_official_v9/contracts/run_identity_registry.json"
    const val V9_VERDICT =
        "integration_evidence/evaluation/ryeong_official_v9/report/V9_VERDICT.json"
    const val V9_DEVIATIONS =
        "integration_evidence/evaluation/ryeong_official_v9/report/V9_POST_FREEZE_DEVIATIONS.json"
    const val K9_RESULT =
        "integration_evidence/evaluation/ryeong_official_v9/result/jvm_keyword/result.json"
    const val K9_STATUS =
        "integration_evidence/evaluation/ryeong_official_v9/result/jvm_keyword/run_status.json"
    const val K9_RAW =
        "integration_evidence/evaluation/ryeong_official_v9/result/jvm_keyword/raw_turns.jsonl"
    const val K8_STATUS =
        "integration_evidence/evaluation/ryeong_official_v8/result/jvm_keyword/run_status.json"
    const val V7_FAILING_SOURCE =
        "app/src/test/java/com/example/hjp/v7char/V7PhaseBVersionPathCharacterizationTest.kt"

    /** Production source v10 must not have touched. */
    val PRODUCTION_ROOTS = listOf(
        "agent-contract/src/main", "agent-core/src/main", "search-core/src/main",
        "tool-contract/src/main", "tool-contact/src/main", "tool-android-intents/src/main",
        "tool-datetime/src/main", "llm-litert/src/main", "app/src/main/java",
    )

    /** The four readers whose byte-identity to v9 is a v10 claim. */
    val CARRIED_READERS = listOf(
        "score_run" to "score_run", "recompute_run" to "recompute_run",
        "compare_readers" to "compare_readers", "validate_run" to "validate_run",
    )

    fun file(relative: String): File = EvidenceRoot.file(relative)

    fun present(relative: String): Boolean = file(relative).isFile

    fun obj(relative: String): JsonObject? {
        val target = file(relative)
        if (!target.isFile) return null
        return json.parseToJsonElement(target.readText(Charsets.UTF_8)) as? JsonObject
    }

    fun requireObj(relative: String): JsonObject =
        obj(relative) ?: error("required v10 artefact is not in the tree: $relative")

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

    fun projections(): Map<String, JsonObject> =
        (obj(PROJECTIONS)?.get("projections") as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap().orEmpty()

    fun projectionRuns(id: String): List<String> = list(projections()[id]?.get("runs"))

    fun consumers(): Map<String, JsonObject> =
        (obj(CONSUMER_CONTRACT)?.get("consumers") as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap().orEmpty()

    fun roots(): Map<String, JsonObject> =
        (obj(LIFECYCLE_CONTRACT)?.get("roots") as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap().orEmpty()

    /** Every string anywhere in a document, so a suite can ask "does this appear at all". */
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
