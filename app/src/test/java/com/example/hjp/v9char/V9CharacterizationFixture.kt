package com.example.hjp.v9char

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
 * What the v9 characterization needs, and nothing it decides.
 *
 * Everything here reads the tree as it is: the frozen v8 contract, the K8 record the v8 runner
 * actually wrote, the two retarget scripts whose lists diverged, and the v9 registry once it exists.
 * Nothing writes, and nothing holds a copy of a value it is supposed to be checking.
 *
 * The parity function below is this file's own reading of what parity means, written from the case
 * table rather than borrowed from the v9 validator. That is deliberate: a characterization that
 * calls the implementation it is characterizing can only report that the implementation agrees with
 * itself.
 */
object V9CharacterizationFixture {

    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    // ---- frozen v8 artefacts ---------------------------------------------------------------------
    const val V8_CROSS_RUN_CONTRACT =
        "tools/ryeong_official_v8/contracts/cross_run_comparison_contract.json"
    const val V8_RUNNER_RETARGET = "tools/ryeong_official_v8/retarget_host_runner_v8.py"
    const val V8_CONTRACT_RETARGET = "tools/ryeong_official_v8/retarget_contracts_v8.py"
    const val K8_RESULT =
        "integration_evidence/evaluation/ryeong_official_v8/result/jvm_keyword/result.json"
    const val K8_STATUS =
        "integration_evidence/evaluation/ryeong_official_v8/result/jvm_keyword/run_status.json"
    const val K8_HOST_VALIDITY =
        "integration_evidence/evaluation/ryeong_official_v8/result/jvm_keyword/host_validity.json"
    const val K8_READER_COMPARISON =
        "integration_evidence/evaluation/ryeong_official_v8/result/jvm_keyword/reader_comparison.json"
    const val V8_COMPARISON =
        "integration_evidence/evaluation/ryeong_official_v8/comparison/" +
            "K3_K4_K5_K6_K7_K8_COMPARISON.json"
    const val V8_HOST_RUNNER = "app/src/test/java/com/example/hjp/eval/v8/RyeongV8OfficialRunTest.kt"

    // ---- v9 artefacts, absent at RED ---------------------------------------------------------------
    const val REGISTRY = "tools/ryeong_official_v9/contracts/run_identity_registry.json"
    const val V9_CROSS_RUN_CONTRACT =
        "tools/ryeong_official_v9/contracts/cross_run_comparison_contract.json"
    const val PARITY_REPORT =
        "integration_evidence/evaluation/ryeong_official_v9/parity/REGISTRY_PARITY.json"
    const val RUNNER_IDENTITY =
        "integration_evidence/evaluation/ryeong_official_v9/registry/RUNNER_IDENTITY.json"
    const val GENERATED_IDENTITY = "tools/ryeong_official_v9/generated/V9RunIdentity.kt"
    const val CASE_TABLE = "tools/ryeong_official_v9/contracts/characterization_cases_v9.json"

    /** Every run the arrangement must account for. */
    val REQUIRED_RUNS = listOf("K3", "K4", "K5", "K6", "K7", "K8", "K9", "D9", "V9_SMOKE")

    /** The identity fields parity is asked about, per run. */
    val IDENTITY_FIELDS = listOf(
        "host_run_id", "device_run_id", "smoke_run_id",
        "host_result_schema", "raw_turn_schema", "device_result_schema",
        "host_output_namespace", "device_official_namespace", "device_smoke_namespace",
        "host_invocation_marker", "device_invocation_marker", "smoke_invocation_marker",
        "metric_source_path", "census_source_path", "runtime_counter_source_path",
        "runtime_mode",
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

    /** The registry's per-run entries, or an empty map when the registry is not there yet. */
    fun registryRuns(): Map<String, JsonObject> =
        (obj(REGISTRY)?.get("runs") as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
            ?.toMap().orEmpty()

    // ------------------------------------------------------------------------------------------------
    // One parity disagreement, with enough to act on.
    // ------------------------------------------------------------------------------------------------

    data class Disagreement(
        val run: String,
        val field: String,
        val registryValue: String?,
        val otherValue: String?,
        val otherSource: String,
    ) {
        override fun toString(): String =
            "$run.$field: registry=$registryValue $otherSource=$otherValue"
    }

    /**
     * Compare one side against the registry, field by field, for every run both declare.
     *
     * A field the registry declares and the other side does not is not a disagreement — not every
     * artefact carries every field, and a device runner has nothing to say about a host metric path.
     * A field both declare and spell differently is.
     */
    fun compareToRegistry(
        other: Map<String, Map<String, String?>>,
        otherSource: String,
        fields: List<String> = IDENTITY_FIELDS,
    ): List<Disagreement> {
        val registry = registryRuns()
        val found = mutableListOf<Disagreement>()
        other.forEach { (run, values) ->
            val entry = registry[run] ?: run {
                found += Disagreement(run, "<entry>", null, "present", otherSource)
                return@forEach
            }
            fields.forEach { field ->
                if (!values.containsKey(field)) return@forEach
                val theirs = values[field]
                val ours = str(entry[field])
                if (theirs != null && ours != theirs) {
                    found += Disagreement(run, field, ours, theirs, otherSource)
                }
            }
        }
        return found
    }

    /** The identity the v9 cross-run contract states, per run, in registry vocabulary. */
    fun contractIdentity(): Map<String, Map<String, String?>> {
        val contract = obj(V9_CROSS_RUN_CONTRACT) ?: return emptyMap()
        val runs = contract["runs"] as? JsonObject ?: return emptyMap()
        return runs.mapNotNull { (key, value) ->
            val entry = value as? JsonObject ?: return@mapNotNull null
            key to buildMap<String, String?> {
                str(entry["run_id"])?.let { put("host_run_id", it) }
                str(entry["expected_result_schema"])?.let { put("host_result_schema", it) }
                str(entry["expected_raw_schema"])?.let { put("raw_turn_schema", it) }
                str(entry["directory"])?.let { put("host_output_namespace", it) }
                str(entry["runtime_mode"])?.let { put("runtime_mode", it) }
            }
        }.toMap()
    }

    /**
     * The identity the *actual historical records* carry — the strongest evidence available.
     *
     * A registry that agrees with a runner's source and disagrees with the record that runner
     * produced has documented an intention, not a fact.
     */
    fun historicalIdentity(): Map<String, Map<String, String?>> {
        val registry = registryRuns()
        val found = mutableMapOf<String, Map<String, String?>>()
        registry.forEach { (run, entry) ->
            val resultPath = str(entry["historical_result_path"]) ?: return@forEach
            val record = obj(resultPath) ?: return@forEach
            val status = str(entry["historical_status_path"])?.let { obj(it) }
            found[run] = buildMap {
                str(record["schema"])?.let { put("host_result_schema", it) }
                str(record["run_id"])?.let { put("host_run_id", it) }
                str(status?.get("run_id"))?.let { put("host_run_id", it) }
            }
        }
        return found
    }
}
