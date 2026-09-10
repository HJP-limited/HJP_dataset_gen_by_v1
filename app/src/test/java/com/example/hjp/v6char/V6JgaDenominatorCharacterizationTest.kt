package com.example.hjp.v6char

import com.example.hjp.eval.ryeong.RyeongCards
import com.example.hjp.eval.ryeong2.EvidenceRoot
import com.example.hjp.eval.ryeong2.RyeongV2ScenarioLoader
import com.example.hjp.eval.v4.ToolSemanticCompletion
import com.example.hjp.eval.v5.V5ContactDependencyContract
import com.example.hjp.eval.v5.V5ContactStore
import com.example.hjp.eval.v6.RyeongV6Scorer
import com.example.hjp.eval.v6.V6Contract
import com.example.hjp.eval.v6.V6Digest
import com.example.hjp.eval.v6.V6DatasetLoader
import com.example.hjp.eval.v6.V6RawReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v5 JGA defect, reproduced against the bytes it actually happened on.
 *
 * RUN_K5's raw record is read here and never written. Two numbers come out of it:
 *
 *  * **223/225** — what the authoritative scorer reported, and what a reader that keeps
 *    `expected_slots: null` out of the denominator gets.
 *  * **223/258** — what `slots = (declared or {}).get("expected_slots") or {}` gets, because that
 *    expression turns an explicit null into an empty declaration and then scores every such turn
 *    that observed anything at all.
 *
 * The 33 turns between them are named, not counted. A count can be reproduced by a different bug;
 * a key set cannot.
 *
 * This test does not re-run, re-score or re-verdict RUN_K5. It reads a frozen artefact and asserts
 * arithmetic about it.
 */
class V6JgaDenominatorCharacterizationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val k5Raw =
        "integration_evidence/evaluation/ryeong_official_v5/result/jvm_keyword/raw_turns.jsonl"

    private fun realDataCase(id: String): JsonObject =
        (V6Contract.cases()["real_data_cases"] as JsonArray)
            .map { it.jsonObject }
            .first { (it["id"] as JsonPrimitive).content == id }

    @Test
    fun `the frozen K5 raw is read and left byte-identical`() {
        val file = EvidenceRoot.file(k5Raw)
        assertTrue("the frozen K5 raw record is missing: $k5Raw", file.isFile)
        val before = V6Digest.ofBytes(file.readBytes())
        file.readText(Charsets.UTF_8)
        val after = V6Digest.ofBytes(file.readBytes())
        assertEquals("reading the K5 raw must not change it", before, after)
        assertEquals(
            "the K5 raw is not the frozen artefact this characterization was written against",
            "d229d6ddf4bc51f7698d6022f19c46a222ceb73f09eda28bd58c7ddf1906c6a5",
            after,
        )
    }

    @Test
    fun `a reader that keeps explicit null out of the denominator reproduces the authority`() {
        val report = scoreK5()
        val case = realDataCase("k5_authoritative_jga")
        val want = case["expect"] as JsonObject
        val jga = report.metrics.getValue("jga")

        assertEquals("JGA numerator", want.int("jga_numerator"), jga.numerator)
        assertEquals("JGA denominator", want.int("jga_denominator"), jga.denominator)

        val census = want["slot_census"] as JsonObject
        assertEquals("slot census scored", census.int("scored"), jga.census.scored)
        assertEquals("slot census not_applicable", census.int("not_applicable"),
            jga.census.notApplicable)
        assertEquals("slot census not_scorable", census.int("not_scorable"), jga.census.notScorable)
        assertEquals("slot census failed_to_run", census.int("failed_to_run"), jga.census.failedToRun)
        assertEquals("slot census contract_error", census.int("contract_error"),
            jga.census.contractError)
        assertEquals("the slot census must account for every turn", 386, jga.census.total)
    }

    @Test
    fun `the v5 legacy fold scores 33 turns the dataset asserts nothing about`() {
        val case = realDataCase("k5_legacy_fold_jga")
        val want = case["expect"] as JsonObject
        val legacy = legacyFoldJga()

        assertEquals("the legacy fold's JGA numerator", want.int("jga_numerator"), legacy.passed)
        assertEquals("the legacy fold's JGA denominator", want.int("jga_denominator"), legacy.scored)
        assertEquals("how many explicit-null turns the fold scored",
            want.int("wrongly_included_count"), legacy.wronglyIncluded.size)
        assertEquals(
            "which explicit-null turns the fold scored",
            (want["wrongly_included_keys"] as JsonArray).map { (it as JsonPrimitive).content }.sorted(),
            legacy.wronglyIncluded.sorted(),
        )
    }

    @Test
    fun `the two readings differ by exactly the explicit-null turns`() {
        val authoritative = scoreK5().metrics.getValue("jga")
        val legacy = legacyFoldJga()
        assertEquals(
            "the fold's denominator is the authority's plus the turns it should have excluded",
            authoritative.denominator!! + legacy.wronglyIncluded.size,
            legacy.scored,
        )
        assertEquals(
            "none of the wrongly included turns passed, so the numerators agree",
            authoritative.numerator, legacy.passed,
        )
        val scored = authoritative.keys.getValue("scored").toSet()
        val overlap = legacy.wronglyIncluded.filter { it in scored }
        assertEquals(
            "no wrongly included turn may also be a legitimately scored one", emptyList<String>(),
            overlap,
        )
    }

    /** The official evaluator's answer for the frozen K5 raw. */
    private fun scoreK5() = RyeongV6Scorer.score(
        dataset = V6DatasetLoader.load(),
        record = V6RawReader.read(EvidenceRoot.file(k5Raw).readText(Charsets.UTF_8)),
        store = V5ContactStore.fromRecords(RyeongCards.load()),
        contract = V5ContactDependencyContract.load(),
        semantics = ToolSemanticCompletion.load(),
        datasetSha256 = V6Digest.ofBytes(
            RyeongV2ScenarioLoader.loadRaw().toByteArray(Charsets.UTF_8),
        ),
        fixtureSha256 = V6Digest.ofBytes(RyeongCards.loadRaw().toByteArray(Charsets.UTF_8)),
        rawSha256 = V6Digest.ofBytes(EvidenceRoot.file(k5Raw).readBytes()),
    )

    private data class LegacyFold(
        val passed: Int,
        val scored: Int,
        val wronglyIncluded: List<String>,
    )

    /**
     * v5's Python expression, restated in Kotlin and applied to the same bytes.
     *
     * This is a pin on the *defect*, not on the fix: it must keep producing 258 for as long as the
     * frozen K5 raw exists, because that is what happened. What changes at GREEN is the reader
     * above, never this.
     */
    private fun legacyFoldJga(): LegacyFold {
        val datasetDocument =
            json.parseToJsonElement(RyeongV2ScenarioLoader.loadRaw()) as JsonObject
        val declaredByKey = mutableMapOf<String, JsonObject>()
        (datasetDocument["scenarios"] as JsonArray).map { it.jsonObject }.forEach { scenario ->
            val index = (scenario["index"] as JsonPrimitive).content
            (scenario["turns"] as JsonArray).map { it.jsonObject }.forEach { turn ->
                declaredByKey["$index/${(turn["depth"] as JsonPrimitive).content}"] = turn
            }
        }

        var scored = 0
        var passed = 0
        val wronglyIncluded = mutableListOf<String>()
        EvidenceRoot.file(k5Raw).readLines(Charsets.UTF_8).filter { it.isNotBlank() }.forEach { line ->
            val row = json.parseToJsonElement(line) as JsonObject
            val key = "${(row["scenario"] as JsonPrimitive).content}/" +
                (row["depth"] as JsonPrimitive).content
            val declared = declaredByKey[key] ?: return@forEach
            if (row["threw"] !is JsonNull) return@forEach

            // `slots = (declared or {}).get("expected_slots") or {}` — an explicit null becomes {}.
            val rawSlots = declared["expected_slots"]
            val slots = if (rawSlots == null || rawSlots is JsonNull) {
                JsonObject(emptyMap())
            } else {
                rawSlots as JsonObject
            }
            val resolved = row.strings("resolved_names")
            val titles = row.strings("plan_titles")
            val locations = row.strings("plan_locations")
            val notScorable = (row["ranking_source"] as JsonPrimitive).content == "NONE" &&
                resolved.isEmpty() && titles.isEmpty() && locations.isEmpty()
            // `if declared is not None and not (...)` — the *turn*, not its slot declaration.
            if (notScorable) return@forEach
            scored += 1
            if (rawSlots is JsonNull) wronglyIncluded += key

            val focusDeclared = slots.containsKey("focus")
            val want = buildSet {
                slots.strings("names").forEach { add("names" to it) }
                slots.strings("titles").forEach { add("titles" to it) }
                slots.strings("locations").forEach { add("locations" to it) }
                val focus = slots["focus"]
                if (focusDeclared && focus != null && focus !is JsonNull) {
                    add("focus" to (focus as JsonPrimitive).content)
                }
            }
            val selected = row["selected_name"]
            val got = buildSet {
                resolved.forEach { add("names" to it) }
                titles.forEach { add("titles" to it) }
                locations.forEach { add("locations" to it) }
                val focus = slots["focus"]
                if (focusDeclared && focus != null && focus !is JsonNull &&
                    selected != null && selected !is JsonNull
                ) {
                    add("focus" to (selected as JsonPrimitive).content)
                }
            }
            if (want == got) passed += 1
        }
        return LegacyFold(passed, scored, wronglyIncluded)
    }

    private fun JsonObject.int(key: String): Int =
        (this[key] as JsonPrimitive).content.toInt()

    private fun JsonObject.strings(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        return (element as JsonArray).map { (it as JsonPrimitive).content }
    }
}
