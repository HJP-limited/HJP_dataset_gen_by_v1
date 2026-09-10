package com.example.hjp.v10char

import com.example.hjp.eval.ryeong2.EvidenceRoot
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What the v10 characterization reads, and nothing it decides.
 *
 * Everything here opens the tree as it is: the frozen v9 registry and the consumers that disagreed
 * with it, the two v10 witnesses built from that tree, and the v10 artefacts once they exist. It
 * holds no copy of a value it is meant to be checking, and it never writes.
 *
 * The predicates below are this file's own reading of what closure and lifecycle mean, written from
 * the case table rather than borrowed from the v10 validators. A characterization that calls the
 * implementation it characterizes can only report that the implementation agrees with itself.
 */
object V10CharacterizationFixture {

    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    // ---- frozen v9, read-only --------------------------------------------------------------------
    const val V9_REGISTRY = "tools/ryeong_official_v9/contracts/run_identity_registry.json"
    const val V9_COMPARISON =
        "integration_evidence/evaluation/ryeong_official_v9/comparison/" +
            "K3_K4_K5_K6_K7_K8_K9_COMPARISON.json"
    const val V9_COMPARISON_VALIDATION =
        "integration_evidence/evaluation/ryeong_official_v9/comparison/" +
            "CROSS_RUN_COMPARISON_VALIDATION.json"
    const val V9_COMPARISON_DRIVER = "tools/ryeong_official_v9/compare_k3_to_k9_v9.py"
    const val V9_FREEZE_CHECKED_PRE =
        "integration_evidence/evaluation/ryeong_official_v9/freeze/checked_paths_pre.jsonl"

    // ---- v10 witnesses -----------------------------------------------------------------------------
    private const val V10 = "integration_evidence/evaluation/ryeong_official_v10"
    const val RUN_SET_WITNESS = "$V10/historical_failure/v9_run_set_enumeration/V9_RUN_SET_WITNESS.json"
    const val DEVIATION_INDEX =
        "$V10/historical_failure/v9_post_freeze_deviations/V9_DEVIATION_INDEX.json"
    const val D1 = "$V10/historical_failure/v9_post_freeze_deviations/" +
        "D1_FROZEN_SOURCE_CHANGED_AFTER_RUN.json"
    const val D2 = "$V10/historical_failure/v9_post_freeze_deviations/" +
        "D2_POST_RUN_FILE_ADDED_TO_FROZEN_ROOT.json"
    const val D3 = "$V10/historical_failure/v9_post_freeze_deviations/" +
        "D3_PRE_RUN_EVIDENCE_OVERWRITTEN.json"

    // ---- v10's own, absent at RED --------------------------------------------------------------------
    const val REGISTRY = "tools/ryeong_official_v10/contracts/run_registry.json"
    const val CONSUMER_CONTRACT = "tools/ryeong_official_v10/contracts/run_set_consumer_contract.json"
    const val LIFECYCLE_CONTRACT =
        "tools/ryeong_official_v10/contracts/evidence_lifecycle_contract.json"
    const val CASE_TABLE = "tools/ryeong_official_v10/contracts/characterization_cases_v10.json"

    const val PROJECTIONS = "$V10/registry/PROJECTIONS.json"
    const val CONSUMER_INVENTORY = "$V10/registry/CONSUMER_INVENTORY.json"
    const val CONSUMER_RUN_SETS = "$V10/registry/CONSUMER_RUN_SET_VALIDATION.json"
    const val ENUMERATION_SCAN = "$V10/registry/RUN_ENUMERATION_SCAN.json"
    const val LIFECYCLE_PATHS = "$V10/lifecycle/LIFECYCLE_PATHS_pre_k10.json"
    const val DRY_RUN = "$V10/dryrun/POST_PROCESSING_DRYRUN.json"

    /** Every run v10 must account for. */
    val REQUIRED_RUNS = listOf("K3", "K4", "K5", "K6", "K7", "K8", "K9", "K10", "D10", "V10_SMOKE")

    /** Membership metadata a registry entry must carry for a projection to be derivable from it. */
    val MEMBERSHIP_FIELDS = listOf(
        "ordinal", "run_kind", "lifecycle_state",
        "include_in_host_comparison", "include_in_identity_parity",
        "include_in_phase_b", "include_in_protected_history",
    )

    /** Roots the lifecycle contract must separate. */
    val REQUIRED_ROOTS = listOf(
        "frozen_source_root", "frozen_pre_run_evidence_root", "official_run_authority_root",
        "post_run_derived_report_root", "delivery_source_root",
        "external_delivery_verification_root", "emergency_hold_report_root",
    )

    fun file(relative: String): File = EvidenceRoot.file(relative)

    fun present(relative: String): Boolean = file(relative).isFile

    fun obj(relative: String): JsonObject? {
        val target = file(relative)
        if (!target.isFile) return null
        return json.parseToJsonElement(target.readText(Charsets.UTF_8)) as? JsonObject
    }

    fun requireObj(relative: String): JsonObject =
        obj(relative) ?: error("characterization input is not in the tree: $relative")

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

    /** The v10 registry's per-run entries, or an empty map before it exists. */
    fun registryRuns(): Map<String, JsonObject> =
        (obj(REGISTRY)?.get("runs") as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap().orEmpty()

    /** The generated projections, by projection id, or empty before they exist. */
    fun projections(): Map<String, JsonObject> =
        (obj(PROJECTIONS)?.get("projections") as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap().orEmpty()

    fun projectionRuns(id: String): List<String> = list(projections()[id]?.get("runs"))

    /** The declared consumers, by consumer id, or empty before the contract exists. */
    fun consumers(): Map<String, JsonObject> =
        (obj(CONSUMER_CONTRACT)?.get("consumers") as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap().orEmpty()

    /** The lifecycle roots, by root id, or empty before the contract exists. */
    fun roots(): Map<String, JsonObject> =
        (obj(LIFECYCLE_CONTRACT)?.get("roots") as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap().orEmpty()

    /** Every declared output path across every root, in declaration order. */
    fun declaredOutputs(): List<Pair<String, String>> =
        roots().flatMap { (rootId, root) ->
            list(root["expected_file_inventory"]).map { rootId to it }
        }

    // ---------------------------------------------------------------------------------------------
    // Predicates written from the case table, not borrowed from the validators.
    // ---------------------------------------------------------------------------------------------

    /** Case-insensitive, normalization-insensitive key for one path. */
    fun collisionKey(path: String): String =
        Normalizer.normalize(path, Normalizer.Form.NFC).lowercase()

    /** Paths that collide under casefold or Unicode normalization, as groups of two or more. */
    fun collisions(paths: List<String>): List<List<String>> =
        paths.groupBy { collisionKey(it) }
            .values
            .filter { group -> group.distinct().size > 1 }
            .map { it.distinct().sorted() }

    /** A run list this fixture reads out of an arbitrary report, wherever the report keeps it. */
    fun runSetOf(report: JsonObject?, vararg fields: String): List<String> {
        fields.forEach { field ->
            val element = at(report, field)
            val direct = list(element)
            if (direct.isNotEmpty()) return direct
            val keys = (element as? JsonObject)?.keys?.sorted()
            if (!keys.isNullOrEmpty()) return keys
        }
        return emptyList()
    }

    /** Which of the frozen v9 sites the witness enumerated, by consumer id. */
    fun witnessSites(): List<JsonObject> =
        (obj(RUN_SET_WITNESS)?.get("manual_enumeration_sites") as? JsonArray)
            ?.filterIsInstance<JsonObject>().orEmpty()
}
