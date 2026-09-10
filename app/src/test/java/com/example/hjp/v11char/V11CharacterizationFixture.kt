package com.example.hjp.v11char

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
 * What the v11 characterization reads, and the reason absence is a failure rather than a shrug.
 *
 * v10's characterization had four cases that passed at RED for the wrong reason: with the artefacts
 * absent, `assertEquals(emptyList(), orphans)` is true because there is nothing to be orphaned.
 * A test that cannot fail when its subject is missing is not testing its subject.
 *
 * So every accessor here comes in two forms. The `maybe` form returns null and is used only where
 * absence is the thing under test. The `require` form throws [FixtureIncomplete], which fails the
 * case loudly with a code a reader can act on. Collections are read through [nonEmpty], which
 * refuses to hand back an empty list that a caller would then vacuously agree with.
 */
object V11CharacterizationFixture {

    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** Thrown when a case's input is not in the tree. A missing subject is a failed case. */
    class FixtureIncomplete(val code: String, message: String) : AssertionError(message)

    const val FIXTURE_INCOMPLETE = "FIXTURE_INCOMPLETE"
    const val FIXTURE_EMPTY = "FIXTURE_EMPTY_COLLECTION"

    // ---- frozen, read-only ------------------------------------------------------------------------
    private const val V10 = "integration_evidence/evaluation/ryeong_official_v10"
    const val V10_COMPARISON = "$V10/post_run/K3_TO_K10_COMPARISON.json"
    const val V10_VERDICT = "$V10/post_run/V10_VERDICT.json"
    const val K10_STATUS = "$V10/result/jvm_keyword/run_status.json"
    const val K10_RESULT = "$V10/result/jvm_keyword/result.json"
    const val K10_RAW = "$V10/result/jvm_keyword/raw_turns.jsonl"
    const val K10_MARKER = "$V10/execution/jvm_keyword/invocation.marker"
    const val K10_HOST_VALIDITY = "$V10/post_run/host_validity_post_k10.json"
    const val K7_STATUS =
        "integration_evidence/evaluation/ryeong_official_v7/result/jvm_keyword/run_status.json"
    const val K7_MARKER =
        "integration_evidence/evaluation/ryeong_official_v7/execution/jvm_keyword/invocation.marker"

    // ---- v11 witnesses over v10 ---------------------------------------------------------------------
    private const val V11 = "integration_evidence/evaluation/ryeong_official_v11"
    const val RUN_STATE_WITNESS =
        "$V11/historical_failure/v10_frozen_run_state/V10_FROZEN_RUN_STATE_WITNESS.json"
    const val STAGE_OUTPUT_WITNESS =
        "$V11/historical_failure/v10_stage_output_lifecycle/V10_STAGE_OUTPUT_WITNESS.json"

    // ---- v11's own, absent at RED ---------------------------------------------------------------------
    const val REGISTRY = "tools/ryeong_official_v11/contracts/run_registry.json"
    const val LIFECYCLE_CONTRACT =
        "tools/ryeong_official_v11/contracts/evidence_lifecycle_contract.json"
    const val GATE_CONTRACT = "tools/ryeong_official_v11/contracts/gate_contract.json"
    const val STATE_CONTRACT = "tools/ryeong_official_v11/contracts/run_state_contract.json"
    const val CASE_TABLE = "tools/ryeong_official_v11/contracts/characterization_cases_v11.json"

    const val PROJECTIONS = "$V11/pre_run/registry/PROJECTIONS.json"
    const val OBSERVED_PRE = "$V11/pre_run/state/OBSERVED_RUN_STATES_pre_k11.json"
    const val OBSERVED_POST = "$V11/post_run/state/OBSERVED_RUN_STATES_post_k11.json"
    const val COMPARISON_PRE = "$V11/pre_run/comparison/K3_TO_K11_COMPARISON_pre_k11.json"
    const val COMPARISON_PRE_VALIDATION =
        "$V11/pre_run/comparison/COMPARISON_VALIDATION_pre_k11.json"
    const val LIFECYCLE_VALIDATION = "$V11/pre_run/lifecycle/LIFECYCLE_PATHS_pre_k11.json"
    const val DRY_RUN = "$V11/pre_run/dryrun/POST_PROCESSING_DRYRUN.json"
    const val MUTATION = "$V11/pre_run/mutation/MUTATION_REPORT.json"
    const val ENUMERATION_SCAN = "$V11/pre_run/registry/RUN_ENUMERATION_SCAN.json"
    const val STATE_SELFTEST = "$V11/pre_run/state/RESOLVER_SELFTEST.json"

    /** Every run v11 must account for. */
    val REQUIRED_RUNS = listOf("K3", "K4", "K5", "K6", "K7", "K8", "K9", "K10", "K11",
                               "D11", "V11_SMOKE")

    /** The execution states the resolver may return. */
    val EXECUTION_STATES = listOf(
        "NOT_YET_EXECUTED", "NOT_RUN_DUE_TO_PREFLIGHT_HOLD", "PRECONDITION_REFUSED",
        "PARTIAL", "COMPLETED", "INCONSISTENT", "UNREADABLE",
    )

    /** The freeze stages, each of which must own a distinct output path. */
    val STAGES = listOf("pre_k11", "post_k11", "post_delivery")

    // ---------------------------------------------------------------------------------------------
    // Accessors. `require` fails; `maybe` is for cases where absence is the subject.
    // ---------------------------------------------------------------------------------------------

    fun file(relative: String): File = EvidenceRoot.file(relative)

    fun exists(relative: String): Boolean = file(relative).isFile

    fun maybeObject(relative: String): JsonObject? {
        val target = file(relative)
        if (!target.isFile) return null
        return runCatching {
            json.parseToJsonElement(target.readText(Charsets.UTF_8)) as? JsonObject
        }.getOrNull()
    }

    /** The document, or a failed case naming the path that was not there. */
    fun requireObject(relative: String): JsonObject {
        val target = file(relative)
        if (!target.isFile) {
            throw FixtureIncomplete(FIXTURE_INCOMPLETE,
                "$FIXTURE_INCOMPLETE: $relative is not in the tree, so this case has no subject")
        }
        val text = target.readText(Charsets.UTF_8)
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: throw FixtureIncomplete(FIXTURE_INCOMPLETE,
                "$FIXTURE_INCOMPLETE: $relative is not parseable JSON")
        return parsed as? JsonObject
            ?: throw FixtureIncomplete(FIXTURE_INCOMPLETE,
                "$FIXTURE_INCOMPLETE: $relative is not a JSON object")
    }

    /** A collection that is allowed to be checked. An empty one is a failed case, not agreement. */
    fun <T> nonEmpty(what: String, values: Collection<T>): Collection<T> {
        if (values.isEmpty()) {
            throw FixtureIncomplete(FIXTURE_EMPTY,
                "$FIXTURE_EMPTY: $what is empty, so any assertion over it would hold for the " +
                    "wrong reason")
        }
        return values
    }

    fun requireText(relative: String): String {
        val target = file(relative)
        if (!target.isFile) {
            throw FixtureIncomplete(FIXTURE_INCOMPLETE,
                "$FIXTURE_INCOMPLETE: $relative is not in the tree")
        }
        return target.readText(Charsets.UTF_8)
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

    /** A required field, or a failed case naming it. */
    fun requireAt(root: JsonObject, path: String): JsonElement {
        return at(root, path)
            ?: throw FixtureIncomplete(FIXTURE_INCOMPLETE,
                "$FIXTURE_INCOMPLETE: the field $path is not present")
    }

    fun requireString(root: JsonObject, path: String): String =
        str(requireAt(root, path))
            ?: throw FixtureIncomplete(FIXTURE_INCOMPLETE,
                "$FIXTURE_INCOMPLETE: the field $path is not a string")

    fun requireInt(root: JsonObject, path: String): Int =
        int(requireAt(root, path))
            ?: throw FixtureIncomplete(FIXTURE_INCOMPLETE,
                "$FIXTURE_INCOMPLETE: the field $path is not an integer")

    fun requireBool(root: JsonObject, path: String): Boolean =
        bool(requireAt(root, path))
            ?: throw FixtureIncomplete(FIXTURE_INCOMPLETE,
                "$FIXTURE_INCOMPLETE: the field $path is not a boolean")

    fun requireList(root: JsonObject, path: String): List<String> {
        val element = requireAt(root, path)
        val values = list(element)
        if (element !is JsonArray) {
            throw FixtureIncomplete(FIXTURE_INCOMPLETE,
                "$FIXTURE_INCOMPLETE: the field $path is not an array")
        }
        return values
    }

    /** Object keys at a pointer, as a set, failing rather than returning nothing. */
    fun requireKeys(root: JsonObject, path: String): Set<String> {
        val element = requireAt(root, path)
        return (element as? JsonObject)?.keys
            ?: throw FixtureIncomplete(FIXTURE_INCOMPLETE,
                "$FIXTURE_INCOMPLETE: the field $path is not an object")
    }

    fun observedState(document: JsonObject, run: String): String =
        requireString(document, "runs/$run/execution_state")

    fun collisionKey(path: String): String =
        Normalizer.normalize(path, Normalizer.Form.NFC).lowercase()

    fun collisions(paths: List<String>): List<List<String>> =
        paths.groupBy { collisionKey(it) }
            .values.filter { it.distinct().size > 1 }
            .map { it.distinct().sorted() }
}
