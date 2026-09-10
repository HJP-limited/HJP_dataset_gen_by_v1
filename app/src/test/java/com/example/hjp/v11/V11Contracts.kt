package com.example.hjp.v11

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

/** Where the v11 artefacts live, in one place, so several suites cannot disagree about it. */
object V11Contracts {

    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private const val V11 = "integration_evidence/evaluation/ryeong_official_v11"
    private const val T11 = "tools/ryeong_official_v11"

    const val REGISTRY = "$T11/contracts/run_registry.json"
    const val STATE_CONTRACT = "$T11/contracts/run_state_contract.json"
    const val LIFECYCLE_CONTRACT = "$T11/contracts/evidence_lifecycle_contract.json"
    const val GATE_CONTRACT = "$T11/contracts/gate_contract.json"
    const val CONSUMER_CONTRACT = "$T11/contracts/run_set_consumer_contract.json"
    const val CASE_TABLE = "$T11/contracts/characterization_cases_v11.json"
    const val METRIC_CONTRACT = "$T11/contracts/metric_scoring_contract.json"

    const val GENERATED_IDENTITY = "$T11/generated/V11RunIdentity.kt"
    const val SOURCE_IDENTITY = "app/src/test/java/com/example/hjp/v11/V11RunIdentity.kt"

    const val PROJECTIONS = "$V11/pre_run/registry/PROJECTIONS.json"
    const val KOTLIN_PARITY = "$V11/pre_run/registry/KOTLIN_PROJECTION_PARITY.json"
    const val ENUMERATION_SCAN = "$V11/pre_run/registry/RUN_ENUMERATION_SCAN.json"
    const val OBSERVED_PRE = "$V11/pre_run/state/OBSERVED_RUN_STATES_pre_k11.json"
    const val RESOLVER_SELFTEST = "$V11/pre_run/state/RESOLVER_SELFTEST.json"
    const val HOLD_COVERAGE = "$V11/pre_run/state/HISTORICAL_HOLD_COVERAGE.json"
    const val COMPARISON_PRE = "$V11/pre_run/comparison/K3_TO_K11_COMPARISON_pre_k11.json"
    const val COMPARISON_PRE_VALIDATION =
        "$V11/pre_run/comparison/COMPARISON_VALIDATION_pre_k11.json"
    const val RUN_STATE_WITNESS =
        "$V11/historical_failure/v10_frozen_run_state/V10_FROZEN_RUN_STATE_WITNESS.json"
    const val STAGE_OUTPUT_WITNESS =
        "$V11/historical_failure/v10_stage_output_lifecycle/V10_STAGE_OUTPUT_WITNESS.json"

    // ---- frozen, read-only ----------------------------------------------------------------------
    const val V10_VERDICT =
        "integration_evidence/evaluation/ryeong_official_v10/post_run/V10_VERDICT.json"
    const val V10_COMPARISON =
        "integration_evidence/evaluation/ryeong_official_v10/post_run/K3_TO_K10_COMPARISON.json"
    const val K10_STATUS =
        "integration_evidence/evaluation/ryeong_official_v10/result/jvm_keyword/run_status.json"
    const val V7_FAILING_SOURCE =
        "app/src/test/java/com/example/hjp/v7char/V7PhaseBVersionPathCharacterizationTest.kt"

    val PRODUCTION_ROOTS = listOf(
        "agent-contract/src/main", "agent-core/src/main", "search-core/src/main",
        "tool-contract/src/main", "tool-contact/src/main", "tool-android-intents/src/main",
        "tool-datetime/src/main", "llm-litert/src/main", "app/src/main/java",
    )

    /** Fields the registry must never carry. Read from the registry itself, not repeated here. */
    fun forbiddenObservedStateFields(): List<String> =
        list(requireObj(REGISTRY)["forbidden_observed_state_fields"])

    fun file(relative: String): File = EvidenceRoot.file(relative)

    fun present(relative: String): Boolean = file(relative).isFile

    fun obj(relative: String): JsonObject? {
        val target = file(relative)
        if (!target.isFile) return null
        return json.parseToJsonElement(target.readText(Charsets.UTF_8)) as? JsonObject
    }

    fun requireObj(relative: String): JsonObject =
        obj(relative) ?: error("required v11 artefact is not in the tree: $relative")

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
        (requireObj(REGISTRY)["runs"] as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap().orEmpty()

    fun projections(): Map<String, JsonObject> =
        (requireObj(PROJECTIONS)["projections"] as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap().orEmpty()

    fun projectionRuns(id: String): List<String> = list(projections()[id]?.get("runs"))

    fun consumers(): Map<String, JsonObject> =
        (requireObj(CONSUMER_CONTRACT)["consumers"] as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap().orEmpty()

    fun observedState(run: String): String? =
        str(at(obj(OBSERVED_PRE), "runs/$run/execution_state"))
}
