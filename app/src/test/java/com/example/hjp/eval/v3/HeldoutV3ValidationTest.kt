package com.example.hjp.eval.v3

import com.example.hjp.eval.EvalOutputPolicy
import com.example.hjp.MultiturnScenarioHarness
import com.example.hjp.eval.EvalRoster
import com.example.hjp.eval.FrozenHeldoutCases
import com.example.hjp.eval.KnownRegressionCases
import com.example.hjp.eval.VisibleGeneralizationCases
import com.example.hjp.eval.v2.HeldoutV2Cases
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural gate on held-out v3, run before the suite is scored.
 *
 * Two rules are new relative to v2, and both close a way v2 could score something wrongly:
 *
 *  - **argument coverage.** Every required argument of every expected tool must be asserted, or the
 *    turn must carry an explicit opt-out with a reason. v2 checked only what a case happened to
 *    declare, so a compose case could pin `card_id` and never look at `to`.
 *  - **turn-scoped forbidden values.** A turn-scoped list may not name a value the same turn is
 *    expected to use; a scenario-wide claim must go in `neverAnywhere`. v2's single scenario-wide
 *    list produced four false failures.
 */
class HeldoutV3ValidationTest {

    private val cases = HeldoutV3Cases.SCENARIOS

    @Test
    fun `held-out v3 is structurally valid`() {
        val problems = mutableListOf<String>()
        fun require(condition: Boolean, message: String) {
            if (!condition) problems += message
        }

        // ---- size and turn distribution -----------------------------------------------------
        val floorTotal = V3Categories.MINIMUMS.values.sum()
        require(cases.size >= floorTotal, "expected >= $floorTotal scenarios, found ${cases.size}")
        require(cases.all { it.userTurnCount >= 2 }, "single-turn scenarios: ${cases.filter { it.userTurnCount < 2 }.map { it.id }}")
        require(cases.count { it.userTurnCount >= 4 } >= 12, "expected >= 12 scenarios with >= 4 turns, found ${cases.count { it.userTurnCount >= 4 }}")
        require(cases.count { it.userTurnCount >= 8 } >= 8, "expected >= 8 scenarios with >= 8 turns, found ${cases.count { it.userTurnCount >= 8 }}")

        // ---- identity ------------------------------------------------------------------------
        val duplicateIds = cases.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty(), "duplicate scenario ids: $duplicateIds")
        val scripts = cases.groupBy { c ->
            c.turns.joinToString("|") { (if (it.resetBefore) "RESET>" else "") + normalize(it.user) }
        }.filterValues { it.size > 1 }
        require(scripts.isEmpty(), "duplicate scenario scripts: ${scripts.values.map { g -> g.map { it.id } }}")
        cases.forEach { c ->
            val repeated = c.turns
                .groupBy { t -> normalize(t.user) + "#" + t.act + t.outcome + t.tools + t.expectedTargetCardId }
                .filterValues { it.size > 1 }
            require(
                repeated.isEmpty() || c.category == V3Categories.IDEMPOTENCY,
                "${c.id} repeats an identical turn without a stated reason: ${repeated.keys}",
            )
        }

        // ---- nothing shared with any suite the implementation has already seen ---------------
        val seenUtterances = (
            VisibleGeneralizationCases.ALL.flatMap { it.turns }.map { it.user } +
                KnownRegressionCases.ALL.flatMap { it.turns }.map { it.user } +
                FrozenHeldoutCases.CASES.flatMap { it.spec.turns }.map { it.user } +
                HeldoutV2Cases.SCENARIOS.flatMap { it.turns }.map { it.user }
            ).map(::normalize).toSet()
        val overlap = cases.flatMap { c -> c.turns.map { normalize(it.user) } }
            .filter { it in seenUtterances }.distinct()
        require(overlap.isEmpty(), "utterances shared with an already-seen suite: $overlap")

        val seenNames = (
            EvalRoster.ALL + MultiturnScenarioHarness.DEFAULT_CARDS + FrozenHeldoutCases.ALL_CARDS +
                com.example.hjp.eval.v2.HeldoutV2Roster.ALL
            ).map { it.name }.toSet()
        val seenIds = (
            EvalRoster.ALL + MultiturnScenarioHarness.DEFAULT_CARDS + FrozenHeldoutCases.ALL_CARDS +
                com.example.hjp.eval.v2.HeldoutV2Roster.ALL
            ).map { it.id }.toSet()
        require(
            HeldoutV3Roster.ALL.none { it.name in seenNames },
            "roster names reused: ${HeldoutV3Roster.ALL.map { it.name }.filter { it in seenNames }}",
        )
        require(
            HeldoutV3Roster.ALL.none { it.id in seenIds },
            "card ids reused: ${HeldoutV3Roster.ALL.map { it.id }.filter { it in seenIds }}",
        )

        // ---- category floors, counted for real -------------------------------------------------
        val counts = cases.groupingBy { it.category }.eachCount()
        V3Categories.MINIMUMS.forEach { (category, floor) ->
            require((counts[category] ?: 0) >= floor, "category $category has ${counts[category] ?: 0}, floor $floor")
        }
        require((counts.keys - V3Categories.ALL).isEmpty(), "undeclared categories: ${counts.keys - V3Categories.ALL}")

        // ---- tools and arguments ----------------------------------------------------------------
        var optOuts = 0
        cases.forEach { c ->
            c.turns.forEachIndexed { i, t ->
                val named = t.tools.toSet() + t.forbidden + t.args.keys + t.argPatterns.keys
                require((named - V3Tools.ALLOWLIST).isEmpty(), "${c.id} turn $i names unknown tools: ${named - V3Tools.ALLOWLIST}")
                require((t.tools.toSet() intersect t.forbidden).isEmpty(), "${c.id} turn $i expects and forbids the same tool")
                val orphan = (t.args.keys + t.argPatterns.keys) - t.tools.toSet()
                require(orphan.isEmpty(), "${c.id} turn $i asserts arguments of unused tools: $orphan")

                // Argument coverage: every required argument of every expected tool, or an opt-out.
                t.tools.distinct().forEach { tool ->
                    val asserted = t.args[tool].orEmpty().keys + t.argPatterns[tool].orEmpty().keys
                    V3Tools.REQUIRED_ARGS.getValue(tool).forEach { arg ->
                        if (arg !in asserted) {
                            val reason = t.argumentOptOut["$tool.$arg"]
                            require(
                                reason != null && reason.isNotBlank(),
                                "${c.id} turn $i leaves required argument $tool.$arg unasserted with no reason",
                            )
                            if (reason != null) optOuts++
                        }
                    }
                    val known = V3Tools.REQUIRED_ARGS.getValue(tool) + V3Tools.OPTIONAL_ARGS.getValue(tool)
                    require((asserted - known).isEmpty(), "${c.id} turn $i asserts unknown arguments ${asserted - known} on $tool")
                }
                t.args.forEach { (_, kv) ->
                    kv.forEach { (key, value) ->
                        V3Tools.ARG_SHAPES[key]?.let { shape ->
                            require(shape.matches(value), "${c.id} turn $i argument $key='$value' has the wrong shape")
                        }
                    }
                }

                val effectTools = t.tools.count { it in V3Tools.SIDE_EFFECTING }
                require(t.sideEffects == effectTools, "${c.id} turn $i declares ${t.sideEffects} side effects but $effectTools side-effecting tools")
                if (t.composeTo != null) require(V3Tools.COMPOSE in t.tools, "${c.id} turn $i asserts a recipient with no compose")
                if (t.calendarAttendees != null) require(V3Tools.CALENDAR in t.tools, "${c.id} turn $i asserts attendees with no calendar call")

                // Response coverage.
                require(t.hasAnswerAssertion, "${c.id} turn $i asserts nothing about the answer")

                // A turn-scoped forbidden value must not be a value the same turn is meant to use.
                val expectedValues = listOfNotNull(t.composeTo) + t.calendarAttendees.orEmpty() +
                    t.args.values.flatMap { it.values }
                val contradiction = t.forbiddenValues.filter { it in expectedValues }
                require(contradiction.isEmpty(), "${c.id} turn $i both forbids and expects $contradiction")

                // Typed obligations carry their own evidence.
                if (t.unsupportedRequest) {
                    require(t.tools.isEmpty(), "${c.id} turn $i is unsupported but expects tools")
                    require(t.answerContains.isNotEmpty() || t.answerContainsAnyOf.isNotEmpty(), "${c.id} turn $i is unsupported with no refusal assertion")
                }
                if (t.generalInformation) {
                    require(t.tools.isEmpty(), "${c.id} turn $i is an information question but expects tools")
                    require(t.semanticTopic != null, "${c.id} turn $i is an information question with no topic assertion")
                }
                if (t.quotedRecall) {
                    require(t.tools.isEmpty(), "${c.id} turn $i is a recall but expects tools")
                    require(t.answerContains.isNotEmpty(), "${c.id} turn $i is a recall with no history assertion")
                }
                if (t.missingRequiredSlot) {
                    require(t.tools.isEmpty() && t.sideEffects == 0, "${c.id} turn $i lacks a required slot but acts")
                    require(t.answerContains.isNotEmpty(), "${c.id} turn $i lacks a required slot without asserting what is asked for")
                }
                require(!(t.expectNoTarget && t.expectedTargetCardId != null), "${c.id} turn $i both expects and forbids a target")
            }
            require(c.intent.isNotBlank(), "${c.id} does not say why it exists")
            // A scenario-wide "never" must not name a value the scenario is expected to use.
            val used = c.turns.flatMap { listOfNotNull(it.composeTo) + it.calendarAttendees.orEmpty() }
            require((c.neverAnywhere intersect used.toSet()).isEmpty(), "${c.id} neverAnywhere contradicts its own expectations")
        }

        // ---- relative dates are pinned ------------------------------------------------------------
        // Checked against the replay clock, not against today. The original check demanded the suite
        // be run on the day it was authored, which made a frozen artefact decay after 24 hours; the
        // property that actually matters is that the dataset declares a reference date at all and
        // that the replay clock agrees with it.
        val replayClock = com.example.hjp.eval.clock.EvaluationClock.fixedAt(
            HeldoutV3Cases.REFERENCE_DATE,
            zoneId = ZoneId.of(HeldoutV3Cases.TIMEZONE),
        )
        require(
            replayClock.today() == HeldoutV3Cases.REFERENCE_DATE,
            "the replay clock resolves to ${replayClock.today()} but the dataset declares " +
                "${HeldoutV3Cases.REFERENCE_DATE}",
        )
        require(replayClock.isFixed, "a frozen dataset must be replayed against a fixed clock")

        // ---- seed reproducibility ------------------------------------------------------------------
        val again = HeldoutV3Cases.SCENARIOS
        assertEquals("not reproducible from its seed", cases.map { it.id }, again.map { it.id })
        assertEquals(
            "surface forms not reproducible from its seed",
            cases.flatMap { c -> c.turns.map { it.user } },
            again.flatMap { c -> c.turns.map { it.user } },
        )

        writeReport(problems, optOuts)
        assertTrue("held-out v3 validation failed:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")

    private fun writeReport(problems: List<String>, optOuts: Int) {
        val turns = cases.flatMap { it.turns }
        val counts = cases.groupingBy { it.category }.eachCount().toSortedMap()
        val buckets = cases.groupingBy { it.userTurnCount }.eachCount().toSortedMap()
        val json = buildString {
            append("{\n")
            append("  \"suite\": \"heldout_v3\",\n")
            append("  \"evaluator_version\": \"${HeldoutV3Evaluator.VERSION}\",\n")
            append("  \"seed\": ${HeldoutV3Cases.SEED},\n")
            append("  \"seed_scope\": \"chooses the search phrasing and mail body of a scenario only; people, ids, shapes and every expected value are static fixtures\",\n")
            append("  \"reference_date\": \"${HeldoutV3Cases.REFERENCE_DATE}\",\n")
            append("  \"timezone\": \"${HeldoutV3Cases.TIMEZONE}\",\n")
            append("  \"locale\": \"${HeldoutV3Cases.LOCALE}\",\n")
            append("  \"scenarios\": ${cases.size},\n")
            append("  \"user_turns\": ${turns.size},\n")
            append("  \"min_user_turns\": ${cases.minOf { it.userTurnCount }},\n")
            append("  \"max_user_turns\": ${cases.maxOf { it.userTurnCount }},\n")
            append("  \"scenarios_with_4_or_more_turns\": ${cases.count { it.userTurnCount >= 4 }},\n")
            append("  \"scenarios_with_8_or_more_turns\": ${cases.count { it.userTurnCount >= 8 }},\n")
            append("  \"turn_count_distribution\": {${buckets.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }}},\n")
            append("  \"by_category\": {${counts.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }}},\n")
            append("  \"category_floors\": {${V3Categories.MINIMUMS.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }}},\n")
            append("  \"turns_with_answer_assertions\": ${turns.count { it.hasAnswerAssertion }},\n")
            append("  \"tool_bearing_turns\": ${turns.count { it.tools.isNotEmpty() }},\n")
            append("  \"multi_tool_turns\": ${turns.count { it.tools.size >= 2 }},\n")
            append("  \"required_slot_turns\": ${turns.count { it.missingRequiredSlot }},\n")
            append("  \"unsupported_turns\": ${turns.count { it.unsupportedRequest }},\n")
            append("  \"information_turns\": ${turns.count { it.generalInformation }},\n")
            append("  \"recall_turns\": ${turns.count { it.quotedRecall }},\n")
            append("  \"reset_turns\": ${turns.count { it.resetBefore }},\n")
            append("  \"turn_scoped_forbidden_value_assertions\": ${turns.sumOf { it.forbiddenValues.size }},\n")
            append("  \"scenario_wide_never_assertions\": ${cases.sumOf { it.neverAnywhere.size }},\n")
            append("  \"required_argument_assertions\": ${turns.sumOf { t -> t.tools.distinct().sumOf { V3Tools.REQUIRED_ARGS.getValue(it).size } }},\n")
            append("  \"required_argument_opt_outs\": $optOuts,\n")
            append("  \"collision_names_probed\": [${HeldoutV3Roster.COLLIDING.joinToString(", ") { "\"${it.name}\"" }}],\n")
            append("  \"distinct_signatures\": ${cases.map { it.signature() }.distinct().size},\n")
            append("  \"tools_covered\": [${turns.flatMap { it.tools }.distinct().sorted().joinToString(", ") { "\"$it\"" }}],\n")
            append("  \"problems\": [${problems.joinToString(", ") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }}],\n")
            append("  \"passed\": ${problems.isEmpty()}\n")
            append("}\n")
        }
        val file = File(V3_RESULT_DIR, "heldout_v3_validation.json")
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    companion object {
        /**
         * Where a replay writes its reports.
         *
         * Deliberately NOT the frozen directory. Writing a report back into the directory that holds
         * the frozen artefacts meant that simply re-running the suite overwrote the frozen validation
         * record — which is exactly what happened, and cost a recovery from the git object database.
         * A run writes to its own run-scoped path; the frozen originals are read-only from here on.
         */
        /**
         * Where a replay writes.
         *
         * Under the current cycle, and checked by [EvalOutputPolicy]: the previous value pointed
         * into `pre_device_v4`, a completed cycle, so a replay whose output differed by a byte
         * would have edited that record. It happened to stay identical, which is luck rather than
         * isolation.
         */
        val V3_RESULT_DIR: String get() =
            EvalOutputPolicy.outputDir(label = "replay").path + "/heldout_v3"

        /** The frozen originals, for reference. Nothing in this package writes here. */
        const val V3_FROZEN_DIR = "../tools/agent_eval/results/pre_device_v3/heldout_v3"
    }
}
