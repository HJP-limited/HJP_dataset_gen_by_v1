package com.example.hjp.v6char

import com.example.hjp.eval.ryeong2.EvidenceRoot
import com.example.hjp.eval.v4.ToolSemanticCompletion
import com.example.hjp.eval.v5.V5ContactDependencyContract
import com.example.hjp.eval.v5.V5ContactStore
import com.example.hjp.eval.v6.RyeongV6Scorer
import com.example.hjp.eval.v6.V6Contract
import com.example.hjp.eval.v6.V6DatasetLoader
import com.example.hjp.eval.v6.V6RawReader
import com.example.hjp.eval.v6.V6Slots
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The characterization cases, applied.
 *
 * One file states every case; this applies it on the Kotlin side and
 * `tools/ryeong_official_v6/judge_cross_implementation_v6.py` applies the same file on the Python
 * side. Two hand-written lists of "the same" cases is how two implementations come to agree about
 * inputs neither of them was actually given.
 *
 * Nothing here is a fixture *for* the implementation: the case file declares what each shape must
 * mean, and this runs the implementation against it. When the implementation is wrong, the case
 * fails; the case is never adjusted to the implementation.
 */
object V6CharacterizationCases {

    private val json = Json { ignoreUnknownKeys = true }

    data class TurnVerdict(
        val id: String,
        val slotState: String,
        val jgaState: String,
        val jgaPass: Boolean?,
        val focusState: String,
        val focusPass: Boolean?,
    )

    data class Expectation(
        val id: String,
        val why: String,
        val slotState: String,
        val jgaState: String,
        val jgaPass: Boolean?,
        val focusState: String,
        val focusPass: Boolean?,
        val reason: String,
    )

    private val document: JsonObject by lazy { V6Contract.cases() }

    fun expectations(): List<Expectation> =
        (document["turn_cases"] as JsonArray).map { it as JsonObject }.map { case ->
            val expect = case["expect"] as JsonObject
            Expectation(
                id = case.string("id"),
                why = case.string("why"),
                slotState = expect.string("slot_state"),
                jgaState = expect.string("jga_state"),
                jgaPass = expect.boolOrNull("jga_pass"),
                focusState = expect.string("focus_state"),
                focusPass = expect.boolOrNull("focus_pass"),
                reason = expect.string("reason"),
            )
        }

    /** The dataset turn and the raw turn a case declares, with `__DELETE__` removing a key. */
    fun materialise(id: String): Pair<JsonObject, JsonObject> {
        val case = (document["turn_cases"] as JsonArray).map { it as JsonObject }
            .firstOrNull { it.string("id") == id }
            ?: error("no characterization case named '$id'")
        val datasetTurn = apply(
            document["base_dataset_turn"] as JsonObject,
            case["dataset_overrides"] as? JsonObject,
        )
        val rawTurn = apply(
            document["base_raw_turn"] as JsonObject,
            case["raw_overrides"] as? JsonObject,
        )
        return datasetTurn to rawTurn
    }

    private fun apply(base: JsonObject, overrides: JsonObject?): JsonObject {
        val result = base.toMutableMap()
        overrides?.forEach { (key, value) ->
            if (value is JsonPrimitive && value.isString && value.content == "__DELETE__") {
                result.remove(key)
            } else {
                result[key] = value
            }
        }
        return JsonObject(result)
    }

    /** Runs one case through the official evaluator and reports where the turn landed. */
    fun judge(id: String): TurnVerdict {
        val (datasetTurn, rawTurn) = materialise(id)
        val datasetDocument = buildJsonObject {
            put("provenance", buildJsonObject {
                put("commit", "characterization")
                put("evaluator_sha256", "characterization")
                put("cards_sha256", "characterization")
                put("seed", 42)
            })
            put("scenarios", buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put("kind", "characterization")
                        put("known_gap", false)
                        put("generate_only", false)
                        put("turn_count", 1)
                        put("turns", JsonArray(listOf(datasetTurn)))
                    },
                )
            })
        }
        val dataset = V6DatasetLoader.parse(datasetDocument.toString())
        val record = V6RawReader.read(rawTurn.toString())
        val report = RyeongV6Scorer.score(
            dataset = dataset,
            record = record,
            store = V5ContactStore.fromRecords(emptyList()),
            contract = V5ContactDependencyContract.load(),
            semantics = ToolSemanticCompletion.load(),
            datasetSha256 = "characterization",
            fixtureSha256 = "characterization",
            rawSha256 = "characterization",
        )
        val key = "0/${(rawTurn["depth"] as JsonPrimitive).content}"
        val slotState = dataset.byKey[key]?.slots?.state?.name
            ?: V6Slots.read(datasetTurn).state.name
        return TurnVerdict(
            id = id,
            slotState = slotState,
            jgaState = stateOf(report.metrics.getValue("jga").keys, key),
            jgaPass = passOf(report.metrics.getValue("jga").keys, key),
            focusState = stateOf(report.metrics.getValue("focus_accuracy").keys, key),
            focusPass = passOf(report.metrics.getValue("focus_accuracy").keys, key),
        )
    }

    /** Which census bucket the key landed in, named as the contract names it. */
    private fun stateOf(keys: Map<String, List<String>>, key: String): String = when {
        keys["scored"].orEmpty().contains(key) -> "SCORED"
        keys["not_applicable"].orEmpty().contains(key) -> "NOT_APPLICABLE"
        keys["not_scorable"].orEmpty().contains(key) -> "NOT_SCORABLE"
        keys["failed_to_run"].orEmpty().contains(key) -> "FAILED_TO_RUN"
        keys["contract_error"].orEmpty().contains(key) -> "CONTRACT_ERROR"
        keys["excluded"].orEmpty().contains(key) -> "EXCLUDED"
        else -> "ABSENT"
    }

    private fun passOf(keys: Map<String, List<String>>, key: String): Boolean? = when {
        keys["passed"].orEmpty().contains(key) -> true
        keys["failed"].orEmpty().contains(key) -> false
        else -> null
    }

    fun evidenceDir(): java.io.File =
        EvidenceRoot.dir("integration_evidence/evaluation/ryeong_official_v6/evaluator_self_test")

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("case is missing '$key'")

    private fun JsonObject.boolOrNull(key: String): Boolean? {
        val element: JsonElement = this[key] ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.content == "null") return null
        return primitive.content.toBooleanStrictOrNull()
    }
}
