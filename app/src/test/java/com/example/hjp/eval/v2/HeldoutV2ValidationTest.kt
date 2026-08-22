package com.example.hjp.eval.v2

import com.example.hjp.eval.EvalOutputPolicy
import com.example.hjp.MultiturnScenarioHarness
import com.example.hjp.eval.EvalRoster
import com.example.hjp.eval.FrozenHeldoutCases
import com.example.hjp.eval.VisibleGeneralizationCases
import com.example.hjp.eval.KnownRegressionCases
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural gate on held-out v2, run before the suite is scored.
 *
 * Everything here is a property the dataset must have for its numbers to mean anything. The rules
 * that matter most are the ones v1 could not state: every scenario is multi-turn, every turn asserts
 * on the answer as well as on the tools, and every category floor is checked by *counting* the
 * scenarios that carry it rather than by checking that a map has a key.
 *
 * If any of this fails the suite must not be run at all — a dataset that cannot justify its own
 * denominators cannot be used to justify a release gate.
 */
class HeldoutV2ValidationTest {

    private val cases = HeldoutV2Cases.SCENARIOS

    @Test
    fun `held-out v2 is structurally valid`() {
        val problems = mutableListOf<String>()
        fun require(condition: Boolean, message: String) {
            if (!condition) problems += message
        }

        // ---- size and turn distribution ------------------------------------------------------
        require(cases.size >= 80, "expected >= 80 scenarios, found ${cases.size}")
        val singleTurn = cases.filter { it.userTurnCount < 2 }
        require(singleTurn.isEmpty(), "single-turn scenarios are not allowed: ${singleTurn.map { it.id }}")
        val fourPlus = cases.count { it.userTurnCount >= 4 }
        require(fourPlus >= 12, "expected >= 12 scenarios with >= 4 user turns, found $fourPlus")
        val eightPlus = cases.count { it.userTurnCount >= 8 }
        require(eightPlus >= 8, "expected >= 8 scenarios with >= 8 user turns, found $eightPlus")

        // ---- identity ------------------------------------------------------------------------
        val duplicateIds = cases.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty(), "duplicate scenario ids: $duplicateIds")

        // The same conversation twice adds no information, whatever its id says. A session reset is
        // part of the conversation: the same sentences with a new-session break in the middle are a
        // different scenario, and this is where that is stated.
        val scripts = cases.groupBy { c ->
            c.turns.joinToString("|") { (if (it.resetBefore) "RESET>" else "") + normalize(it.user) }
        }
        val duplicateScripts = scripts.filterValues { it.size > 1 }
        require(
            duplicateScripts.isEmpty(),
            "duplicate scenario scripts: ${duplicateScripts.values.map { g -> g.map { it.id } }}",
        )

        // Two scenarios may legitimately share a sentence, and a scenario may legitimately ask the
        // same question twice: the same attribute question about a *different* person is exactly what
        // a target-switch probe is for. What counts as padding is the same sentence expecting the
        // same thing, so the key is the sentence plus everything the turn asserts about it.
        cases.forEach { c ->
            val repeated = c.turns
                .groupBy { t -> normalize(t.user) + "#" + t.act + t.outcome + t.tools + t.selectedCardId }
                .filterValues { it.size > 1 }
            require(
                repeated.isEmpty() || c.category == V2Categories.IDEMPOTENCY,
                "${c.id} repeats an identical turn without a stated reason: ${repeated.keys}",
            )
        }

        // ---- no overlap with any suite the implementation has already seen --------------------
        val visibleUtterances = (
            VisibleGeneralizationCases.ALL.flatMap { it.turns } +
                KnownRegressionCases.ALL.flatMap { it.turns } +
                FrozenHeldoutCases.CASES.flatMap { it.spec.turns }
            ).map { normalize(it.user) }.toSet()
        val overlap = cases.flatMap { c -> c.turns.map { normalize(it.user) } }
            .filter { it in visibleUtterances }
            .distinct()
        require(overlap.isEmpty(), "utterances shared with an already-seen suite: $overlap")

        val seenNames = (
            EvalRoster.ALL + MultiturnScenarioHarness.DEFAULT_CARDS + FrozenHeldoutCases.ALL_CARDS
            ).map { it.name }.toSet()
        val seenIds = (
            EvalRoster.ALL + MultiturnScenarioHarness.DEFAULT_CARDS + FrozenHeldoutCases.ALL_CARDS
            ).map { it.id }.toSet()
        val nameCollisions = HeldoutV2Roster.ALL.map { it.name }.filter { it in seenNames }
        val idCollisions = HeldoutV2Roster.ALL.map { it.id }.filter { it in seenIds }
        require(nameCollisions.isEmpty(), "roster names reused from an earlier suite: $nameCollisions")
        require(idCollisions.isEmpty(), "card ids reused from an earlier suite: $idCollisions")

        // ---- category floors, counted for real -----------------------------------------------
        val counts = cases.groupingBy { it.category }.eachCount()
        V2Categories.MINIMUMS.forEach { (category, floor) ->
            val actual = counts[category] ?: 0
            require(actual >= floor, "category $category has $actual scenarios, floor is $floor")
        }
        val unknown = counts.keys - V2Categories.ALL
        require(unknown.isEmpty(), "scenarios in undeclared categories: $unknown")

        // ---- tools ----------------------------------------------------------------------------
        cases.forEach { c ->
            c.turns.forEachIndexed { i, t ->
                val named = t.tools.toSet() + t.forbidden + t.args.keys + t.argPatterns.keys
                val bad = named - V2Tools.ALLOWLIST
                require(bad.isEmpty(), "${c.id} turn $i names tools outside the allowlist: $bad")

                // A tool cannot be both expected and forbidden in the same turn.
                val contradiction = t.tools.toSet() intersect t.forbidden
                require(
                    contradiction.isEmpty(),
                    "${c.id} turn $i both expects and forbids: $contradiction",
                )

                // An argument assertion for a tool the turn does not run can never be evaluated.
                val orphanArgs = (t.args.keys + t.argPatterns.keys) - t.tools.toSet()
                require(orphanArgs.isEmpty(), "${c.id} turn $i asserts arguments of unused tools: $orphanArgs")

                t.args.forEach { (tool, kv) ->
                    val allowed = V2Tools.REQUIRED_ARGS[tool].orEmpty() +
                        setOf("purpose", "limit", "subject", "end_time", "location", "description", "timezone")
                    val strange = kv.keys - allowed
                    require(strange.isEmpty(), "${c.id} turn $i asserts unknown arguments $strange on $tool")
                    kv.forEach { (key, value) ->
                        V2Tools.ARG_SHAPES[key]?.let { shape ->
                            require(
                                shape.matches(value),
                                "${c.id} turn $i argument $key='$value' does not have the shape of a $key",
                            )
                        }
                    }
                }

                // Side effects and side-effecting tools have to agree.
                val effectTools = t.tools.count { it in V2Tools.SIDE_EFFECTING }
                require(
                    t.sideEffects == effectTools,
                    "${c.id} turn $i declares ${t.sideEffects} side effects but ${effectTools} " +
                        "side-effecting tools",
                )
                if (t.composeTo != null) {
                    require(
                        V2Tools.COMPOSE in t.tools,
                        "${c.id} turn $i asserts a recipient without opening a compose screen",
                    )
                }
                if (t.calendarAttendees != null) {
                    require(
                        V2Tools.CALENDAR in t.tools,
                        "${c.id} turn $i asserts attendees without a calendar call",
                    )
                }

                // ---- every turn checks the answer ---------------------------------------------
                require(
                    t.hasAnswerAssertion || t.semanticTopic != null,
                    "${c.id} turn $i asserts nothing about the answer, so it could never show the " +
                        "answer was right",
                )

                // ---- typed obligations carry their own evidence -------------------------------
                if (t.unsupportedRequest) {
                    require(t.tools.isEmpty(), "${c.id} turn $i is unsupported but expects tools")
                    require(
                        t.answerContains.isNotEmpty() || t.answerContainsAnyOf.isNotEmpty(),
                        "${c.id} turn $i is unsupported without an explicit refusal assertion",
                    )
                }
                if (t.generalInformation) {
                    require(t.tools.isEmpty(), "${c.id} turn $i is an information question but expects tools")
                    require(
                        t.semanticTopic != null,
                        "${c.id} turn $i is an information question with no topic assertion",
                    )
                }
                if (t.quotedRecall) {
                    require(t.tools.isEmpty(), "${c.id} turn $i is a recall but expects tools")
                    require(
                        t.answerContains.isNotEmpty(),
                        "${c.id} turn $i is a recall with no history-based answer assertion",
                    )
                }
                if (t.missingRequiredSlot) {
                    require(t.tools.isEmpty(), "${c.id} turn $i lacks a required slot but expects tools")
                    require(t.sideEffects == 0, "${c.id} turn $i lacks a required slot but acts")
                    require(
                        t.answerContains.isNotEmpty(),
                        "${c.id} turn $i lacks a required slot without asserting what is asked for",
                    )
                }
            }

            require(c.intent.isNotBlank(), "${c.id} does not say why it exists")
        }

        // ---- relative dates are pinned ---------------------------------------------------------
        val replayClock = com.example.hjp.eval.clock.EvaluationClock.fixedAt(
            HeldoutV2Cases.REFERENCE_DATE,
            zoneId = ZoneId.of(HeldoutV2Cases.TIMEZONE),
        )
        require(
            replayClock.today() == HeldoutV2Cases.REFERENCE_DATE,
            "the replay clock resolves to ${replayClock.today()} but the dataset declares " +
                "${HeldoutV2Cases.REFERENCE_DATE}",
        )

        // ---- seed reproducibility --------------------------------------------------------------
        // Building twice must produce the same suite, or "seeded" would mean "random".
        val again = HeldoutV2Cases.SCENARIOS
        assertEquals("the suite is not reproducible from its seed", cases.map { it.id }, again.map { it.id })
        assertEquals(
            "the suite's surface forms are not reproducible from its seed",
            cases.flatMap { c -> c.turns.map { it.user } },
            again.flatMap { c -> c.turns.map { it.user } },
        )

        // Self-contradictory cases are reported, never silently dropped and never scored.
        val invalid = DatasetConsistency.invalid(cases)
        writeInvalidReport(invalid)

        writeReport(problems)
        assertTrue("held-out v2 validation failed:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")

    private fun writeReport(problems: List<String>) {
        val turns = cases.sumOf { it.userTurnCount }
        val counts = cases.groupingBy { it.category }.eachCount().toSortedMap()
        val buckets = cases.groupingBy { it.userTurnCount }.eachCount().toSortedMap()
        val toolTurns = cases.flatMap { it.turns }.filter { it.tools.isNotEmpty() }
        val json = buildString {
            append("{\n")
            append("  \"suite\": \"heldout_v2\",\n")
            append("  \"evaluator_version\": \"${HeldoutV2Evaluator.VERSION}\",\n")
            append("  \"seed\": ${HeldoutV2Cases.SEED},\n")
            append("  \"seed_scope\": \"chooses the search phrasing and the mail body of a scenario " +
                "only; people, card ids, conversation shapes, tool traces and every expected value " +
                "are static fixtures\",\n")
            append("  \"reference_date\": \"${HeldoutV2Cases.REFERENCE_DATE}\",\n")
            append("  \"timezone\": \"${HeldoutV2Cases.TIMEZONE}\",\n")
            append("  \"locale\": \"${HeldoutV2Cases.LOCALE}\",\n")
            append("  \"scenarios\": ${cases.size},\n")
            append("  \"user_turns\": $turns,\n")
            append("  \"min_user_turns\": ${cases.minOf { it.userTurnCount }},\n")
            append("  \"max_user_turns\": ${cases.maxOf { it.userTurnCount }},\n")
            append("  \"scenarios_with_4_or_more_turns\": ${cases.count { it.userTurnCount >= 4 }},\n")
            append("  \"scenarios_with_8_or_more_turns\": ${cases.count { it.userTurnCount >= 8 }},\n")
            append("  \"turn_count_distribution\": {")
            append(buckets.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" })
            append("},\n")
            append("  \"by_category\": {")
            append(counts.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" })
            append("},\n")
            append("  \"category_floors\": {")
            append(V2Categories.MINIMUMS.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" })
            append("},\n")
            append("  \"turns_with_answer_assertions\": ")
            append(cases.flatMap { it.turns }.count { it.hasAnswerAssertion || it.semanticTopic != null })
            append(",\n")
            append("  \"tool_bearing_turns\": ${toolTurns.size},\n")
            append("  \"multi_tool_turns\": ${toolTurns.count { it.tools.size >= 2 }},\n")
            append("  \"required_slot_turns\": ${cases.flatMap { it.turns }.count { it.missingRequiredSlot }},\n")
            append("  \"unsupported_turns\": ${cases.flatMap { it.turns }.count { it.unsupportedRequest }},\n")
            append("  \"information_turns\": ${cases.flatMap { it.turns }.count { it.generalInformation }},\n")
            append("  \"recall_turns\": ${cases.flatMap { it.turns }.count { it.quotedRecall }},\n")
            append("  \"reset_turns\": ${cases.flatMap { it.turns }.count { it.resetBefore }},\n")
            append("  \"distinct_signatures\": ${cases.map { it.signature() }.distinct().size},\n")
            append("  \"tools_covered\": [")
            append(toolTurns.flatMap { it.tools }.distinct().sorted().joinToString(", ") { "\"$it\"" })
            append("],\n")
            append("  \"problems\": [")
            append(problems.joinToString(", ") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
            append("],\n")
            append("  \"passed\": ${problems.isEmpty()}\n")
            append("}\n")
        }
        val file = File(V2_RESULT_DIR, "heldout_v2_validation.json")
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    companion object {
        /**
         * Run-scoped, never the frozen directory. See HeldoutV3ValidationTest for why.
         */
        /** Under the current cycle. See HeldoutV3ValidationTest for why. */
        val V2_RESULT_DIR: String get() =
            EvalOutputPolicy.outputDir(label = "replay").path + "/heldout_v2"
    }

    /** DATASET_INVALID, written out so the count and the reasons are public. */
    private fun writeInvalidReport(invalid: Map<String, List<DatasetConsistency.Contradiction>>) {
        val json = buildString {
            append("{\n  \"suite\": \"heldout_v2\",\n")
            append("  \"check\": \"dataset_consistency\",\n")
            append("  \"rule\": \"a case that requires and forbids the same value, tool, card id or ")
            append("target cannot be satisfied by any behaviour, so scoring it measures nothing\",\n")
            append("  \"total_scenarios\": ${cases.size},\n")
            append("  \"dataset_invalid\": ${invalid.size},\n")
            append("  \"valid_denominator\": ${cases.size - invalid.size},\n")
            append("  \"original_fixture_modified\": false,\n")
            append("  \"invalid_cases\": [")
            append(
                invalid.entries.joinToString(", ") { (id, reasons) ->
                    "{\"id\": \"" + id + "\", \"contradictions\": [" +
                        reasons.joinToString(", ") { r ->
                            "{\"kind\": \"" + r.kind + "\", \"turn\": " + r.turn +
                                ", \"detail\": \"" + r.detail.replace("\"", "'") + "\"}"
                        } + "]}"
                },
            )
            append("]\n}\n")
        }
        val file = File(V2_RESULT_DIR, "heldout_v2_dataset_consistency.json")
        file.parentFile?.mkdirs()
        file.writeText(json)
    }
}
