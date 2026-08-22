package com.example.hjp.eval.ryeong

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The frozen Ryeong multiturn scenarios, as exported from upstream.
 *
 * This is a reader, not a builder. Every field comes from `tools/ryeong_multiturn_v4` output,
 * which itself calls upstream's own `build_scenarios()`. Nothing here invents a scenario, a
 * question, an expected route or a gold ID.
 */
data class RyeongTurn(
    val depth: Int,
    val question: String,
    /** Upstream's expected route. Null means the turn asserts no route at all. */
    val expectedRoute: String?,
    /** Upstream's expected slots, or null when the turn asserts no slots. */
    val expectedSlots: RyeongSlots?,
    val goldCardIds: List<String>,
    /** Each element is a list of alternatives; one match is enough. */
    val must: List<List<String>>,
    val mustNot: List<String>,
    val noCards: Boolean,
)

/**
 * Upstream's slot surface.
 *
 * `focusDeclared` is deliberately separate from `focus`: upstream's `slot_sets()` compares focus
 * only when the scenario declared the key, so "declared as null" and "not declared" are different
 * assertions and must not collapse into one.
 */
data class RyeongSlots(
    val names: List<String>,
    val titles: List<String>,
    val locations: List<String>,
    val focusDeclared: Boolean,
    val focus: String?,
)

data class RyeongScenario(
    val index: Int,
    val kind: String,
    val knownGap: Boolean,
    val generateOnly: Boolean,
    val turns: List<RyeongTurn>,
) {
    val turnCount: Int get() = turns.size

    val depthBucket: String
        get() = when {
            turnCount == 1 -> "depth_1"
            turnCount == 2 -> "depth_2"
            turnCount <= 5 -> "depth_3_5"
            turnCount <= 10 -> "depth_6_10"
            else -> "depth_11_plus"
        }
}

data class RyeongScenarioSet(
    val commit: String,
    val evaluatorSha256: String,
    val cardsSha256: String,
    val seed: Int,
    val scenarios: List<RyeongScenario>,
) {
    val turnCount: Int get() = scenarios.sumOf { it.turnCount }
    val kinds: Set<String> get() = scenarios.map { it.kind }.toSet()
}

object RyeongScenarioLoader {

    const val RESOURCE = "/ryeong/scenarios_v1.json"

    /** The inventory the freeze declares. Loading refuses to hand back anything else. */
    const val EXPECTED_SCENARIOS = 130
    const val EXPECTED_TURNS = 377
    const val EXPECTED_KINDS = 21
    const val EXPECTED_KNOWN_GAP = 0
    const val EXPECTED_GENERATE_ONLY = 6

    private val json = Json { ignoreUnknownKeys = true }

    fun loadRaw(): String = requireNotNull(
        RyeongScenarioLoader::class.java.getResourceAsStream(RESOURCE),
    ) { "frozen scenario manifest missing from test resources: $RESOURCE" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** Parses and applies the exact inventory gate. Throws rather than return a partial set. */
    fun load(): RyeongScenarioSet = parse(loadRaw())

    /** Same contract as [load], on text supplied by the caller. Used to test the gate itself. */
    fun parse(raw: String): RyeongScenarioSet {
        val root = json.parseToJsonElement(raw).jsonObjectOrFail("root")
        val provenance = root["provenance"].jsonObjectOrFail("provenance")
        val scenarios = root["scenarios"].jsonArrayOrFail("scenarios")
            .map { it.jsonObjectOrFail("scenario").toScenario() }

        val set = RyeongScenarioSet(
            commit = provenance.string("commit"),
            evaluatorSha256 = provenance.string("evaluator_sha256"),
            cardsSha256 = provenance.string("cards_sha256"),
            seed = provenance.int("seed"),
            scenarios = scenarios,
        )
        gate(set)
        return set
    }

    private fun gate(set: RyeongScenarioSet) {
        val problems = buildList {
            if (set.scenarios.size != EXPECTED_SCENARIOS) {
                add("scenarios ${set.scenarios.size} != $EXPECTED_SCENARIOS")
            }
            if (set.turnCount != EXPECTED_TURNS) add("turns ${set.turnCount} != $EXPECTED_TURNS")
            if (set.kinds.size != EXPECTED_KINDS) add("kinds ${set.kinds.size} != $EXPECTED_KINDS")
            val gaps = set.scenarios.count { it.knownGap }
            if (gaps != EXPECTED_KNOWN_GAP) add("known_gap $gaps != $EXPECTED_KNOWN_GAP")
            val genOnly = set.scenarios.count { it.generateOnly }
            if (genOnly != EXPECTED_GENERATE_ONLY) {
                add("generate_only $genOnly != $EXPECTED_GENERATE_ONLY")
            }
            val duplicated = set.scenarios.groupBy { it.index }.filterValues { it.size > 1 }.keys
            if (duplicated.isNotEmpty()) add("duplicate scenario indices $duplicated")
            if (set.seed != 42) add("seed ${set.seed} != 42")
        }
        require(problems.isEmpty()) { "frozen scenario gate failed: ${problems.joinToString("; ")}" }
    }

    private fun JsonObject.toScenario() = RyeongScenario(
        index = int("index"),
        kind = string("kind"),
        knownGap = bool("known_gap"),
        generateOnly = bool("generate_only"),
        turns = this["turns"].jsonArrayOrFail("turns").map { it.jsonObjectOrFail("turn").toTurn() },
    )

    private fun JsonObject.toTurn(): RyeongTurn {
        val route = this["expected_route"]
        val slots = this["expected_slots"]
        return RyeongTurn(
            depth = int("depth"),
            question = string("question"),
            expectedRoute = if (route == null || route is JsonNull) null else route.stringValue(),
            expectedSlots = if (slots == null || slots is JsonNull) {
                null
            } else {
                slots.jsonObjectOrFail("expected_slots").toSlots()
            },
            goldCardIds = this["gold_card_ids"].jsonArrayOrFail("gold_card_ids")
                .map { it.stringValue() },
            must = this["must"].jsonArrayOrFail("must")
                .map { alt -> alt.jsonArrayOrFail("must alternative").map { it.stringValue() } },
            mustNot = this["must_not"].jsonArrayOrFail("must_not").map { it.stringValue() },
            noCards = bool("no_cards"),
        )
    }

    private fun JsonObject.toSlots(): RyeongSlots {
        val focusElement = this["focus"]
        return RyeongSlots(
            names = stringList("names"),
            titles = stringList("titles"),
            locations = stringList("locations"),
            // Upstream's slot_sets() only compares focus when the key is present at all.
            focusDeclared = containsKey("focus"),
            focus = if (focusElement == null || focusElement is JsonNull) {
                null
            } else {
                focusElement.stringValue()
            },
        )
    }

    // ---- strict, type-checked accessors ---------------------------------------------------------
    // A missing key is an error, never an empty default: silently reading absence as "no value"
    // is exactly how an unasserted field turns into a passing assertion.

    private fun JsonElement?.jsonObjectOrFail(what: String): JsonObject =
        this as? JsonObject ?: error("$what is not a JSON object: $this")

    private fun JsonElement?.jsonArrayOrFail(what: String): JsonArray =
        this as? JsonArray ?: error("$what is not a JSON array: $this")

    private fun JsonElement.stringValue(): String {
        val primitive = this as? JsonPrimitive ?: error("expected a string, got $this")
        require(primitive.isString) { "expected a JSON string, got $primitive" }
        return primitive.content
    }

    private fun JsonObject.string(key: String): String =
        (this[key] ?: error("missing key '$key'")).stringValue()

    private fun JsonObject.int(key: String): Int {
        val primitive = this[key] as? JsonPrimitive ?: error("missing or non-numeric '$key'")
        require(!primitive.isString) { "'$key' must be a JSON number, not a string" }
        return primitive.intOrNull ?: error("'$key' is not an integer: $primitive")
    }

    private fun JsonObject.bool(key: String): Boolean {
        val primitive = this[key] as? JsonPrimitive ?: error("missing or non-boolean '$key'")
        require(!primitive.isString) { "'$key' must be a JSON boolean, not a string" }
        return primitive.booleanOrNull ?: error("'$key' is not a boolean: $primitive")
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        return element.jsonArrayOrFail(key).map { it.stringValue() }
    }
}
