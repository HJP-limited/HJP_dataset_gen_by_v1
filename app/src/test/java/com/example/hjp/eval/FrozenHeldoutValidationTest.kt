package com.example.hjp.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pre-run validation of the frozen held-out dataset.
 *
 * Runs no kernel and executes no production behaviour: everything here reads the declared cases and
 * compares them with the visible and known-regression datasets. It exists so the dataset can be
 * checked and digest-frozen *before* the single scored run, rather than being adjusted afterwards.
 */
class FrozenHeldoutValidationTest {
    private val cases = FrozenHeldoutCases.CASES
    private val specs = FrozenHeldoutCases.ALL
    private val visible = VisibleGeneralizationCases.ALL

    private fun script(spec: MultiturnSpec) = spec.turns.joinToString("") { it.user }
    private fun normalize(text: String) = text
        .replace(Regex("\\s+"), "")
        .replace(Regex("[.!?,'‘’\"]"), "")
        .lowercase()

    @Test
    fun `held-out dataset is valid, novel and fully covered`() {
        val problems = mutableListOf<String>()

        // ---- schema and mandatory assertions ---------------------------------------------------
        var turnsWithoutAct = 0
        var turnsWithoutOutcome = 0
        specs.forEach { spec ->
            if (spec.turns.isEmpty()) problems += "${spec.id}: no turns"
            if (spec.primaryCategory !in EvalCategories.MINIMUMS.keys) {
                problems += "${spec.id}: unknown primary category ${spec.primaryCategory}"
            }
            spec.turns.forEachIndexed { index, turn ->
                if (turn.user.isBlank()) problems += "${spec.id}#$index: blank utterance"
                if (turn.expectedAct == null) {
                    turnsWithoutAct++
                    problems += "${spec.id}#$index: no expected route"
                }
                if (turn.expectedOutcome == null) {
                    turnsWithoutOutcome++
                    problems += "${spec.id}#$index: no expected outcome"
                }
            }
        }
        cases.forEach { case ->
            if (case.provenance.isBlank()) problems += "${case.spec.id}: no provenance"
            if (case.polarity !in setOf("positive", "negative")) {
                problems += "${case.spec.id}: bad polarity ${case.polarity}"
            }
        }

        // ---- novelty against visible and known --------------------------------------------------
        val visibleScripts = visible.map { script(it) }.toSet()
        val visibleNormalized = visible.map { normalize(script(it)) }.toSet()
        val visibleUtterances = visible.flatMap { it.turns.map { turn -> turn.user } }.toSet()
        val visibleNormalizedUtterances = visibleUtterances.map(::normalize).toSet()
        val visibleTemplates = visible.map { it.templateSignature() }.toSet()

        val exactScriptOverlap = specs.filter { script(it) in visibleScripts }.map { it.id }
        val normalizedScriptOverlap =
            specs.filter { normalize(script(it)) in visibleNormalized }.map { it.id }
        val utteranceOverlap = specs.flatMap { spec ->
            spec.turns.map { it.user }.filter { it in visibleUtterances }.map { "${spec.id}: $it" }
        }
        val normalizedUtteranceOverlap = specs.flatMap { spec ->
            spec.turns.map { it.user }
                .filter { normalize(it) in visibleNormalizedUtterances }
                .map { "${spec.id}: $it" }
        }

        // ---- novelty inside the held-out set itself ---------------------------------------------
        val internalExact = specs.groupBy { script(it) }
            .filterValues { it.size > 1 }.values.flatten().map { it.id }.sorted()
        val internalNormalized = specs.groupBy { normalize(script(it)) }
            .filterValues { it.size > 1 }.values.flatten().map { it.id }.sorted()

        // ---- name-only template clones -----------------------------------------------------------
        // A family that only swapped the person would produce the same shape *and* the same wording
        // once names are masked. Masking every roster name is what separates a real variant from a
        // rename.
        val names = FrozenHeldoutCases.ALL_CARDS.map { it.name }.distinct()
        fun maskNames(text: String): String =
            names.fold(text) { acc, name -> acc.replace(name, "<PERSON>") }
        val maskedGroups = specs.groupBy { spec ->
            spec.templateSignature() + "||" + spec.turns.joinToString("") { maskNames(it.user) }
        }
        val nameOnlyClones = maskedGroups.filterValues { it.size > 1 }
            .values.flatten().map { it.id }.sorted()

        // ---- id and person disjointness with the known regressions --------------------------------
        val knownNames = KnownRegressionCases.ALL
            .flatMap { spec -> spec.cards.map { it.name } }.toSet()
        val knownIds = KnownRegressionCases.ALL
            .flatMap { spec -> spec.cards.map { it.id } }.toSet()
        val heldoutNames = FrozenHeldoutCases.ALL_CARDS.map { it.name }.toSet()
        val heldoutIds = FrozenHeldoutCases.ALL_CARDS.map { it.id }.toSet()
        val nameCollisions = heldoutNames intersect knownNames
        val idCollisions = heldoutIds intersect knownIds
        val rosterNameCollisions = heldoutNames intersect EvalRoster.ALL.map { it.name }.toSet()
        val rosterIdCollisions = heldoutIds intersect EvalRoster.ALL.map { it.id }.toSet()

        // ---- coverage ------------------------------------------------------------------------------
        val byCategory = specs.groupingBy { it.primaryCategory }.eachCount()
        val allTools = specs.flatMap { spec -> spec.turns.flatMap { it.expectedTools } }.toSet()
        val requiredTools = setOf(
            FrozenHeldoutCases.SEARCH, FrozenHeldoutCases.GET, FrozenHeldoutCases.COMPOSE,
            FrozenHeldoutCases.CALENDAR, FrozenHeldoutCases.UPDATE, FrozenHeldoutCases.NOW,
        )
        val chains = specs.map { spec ->
            spec.turns.map { it.expectedTools }.filter { it.isNotEmpty() }
                .joinToString(" ; ") { it.joinToString("+") }
        }
        val multiToolTurns = specs.sumOf { spec -> spec.turns.count { it.expectedTools.size >= 2 } }
        val positives = cases.count { it.polarity == "positive" }
        val negatives = cases.count { it.polarity == "negative" }
        val turnCounts = specs.map { it.userTurnCount }
        val lengthBuckets = specs.groupingBy { bucket(it.userTurnCount) }.eachCount()

        // ---- report ---------------------------------------------------------------------------------
        val json = buildString {
            append("{\n")
            append("  \"suite\": \"frozen_heldout\",\n")
            append("  \"stage\": \"pre_run_validation\",\n")
            append("  \"note\": ").append(
                EvalReport.q(
                    "no kernel is executed here; this stage only inspects declared cases and " +
                        "compares them with the visible and known datasets",
                ),
            ).append(",\n")
            append("  \"seed\": ").append(FrozenHeldoutCases.SEED).append(",\n")
            append("  \"total_cases\": ").append(specs.size).append(",\n")
            append("  \"total_turns\": ").append(turnCounts.sum()).append(",\n")
            append("  \"turns_without_expected_route\": ").append(turnsWithoutAct).append(",\n")
            append("  \"turns_without_expected_outcome\": ").append(turnsWithoutOutcome).append(",\n")
            append("  \"cases_without_provenance\": ")
            append(cases.count { it.provenance.isBlank() }).append(",\n")
            append("  \"exact_script_overlap_with_visible\": ")
            append(EvalReport.arr(exactScriptOverlap)).append(",\n")
            append("  \"normalized_script_overlap_with_visible\": ")
            append(EvalReport.arr(normalizedScriptOverlap)).append(",\n")
            append("  \"exact_utterance_overlap_with_visible\": ")
            append(EvalReport.arr(utteranceOverlap)).append(",\n")
            append("  \"normalized_utterance_overlap_with_visible\": ")
            append(EvalReport.arr(normalizedUtteranceOverlap)).append(",\n")
            append("  \"internal_exact_duplicates\": ").append(EvalReport.arr(internalExact)).append(",\n")
            append("  \"internal_normalized_duplicates\": ")
            append(EvalReport.arr(internalNormalized)).append(",\n")
            append("  \"name_only_template_clones\": ").append(EvalReport.arr(nameOnlyClones)).append(",\n")
            append("  \"person_name_collisions_with_known_regressions\": ")
            append(EvalReport.arr(nameCollisions.toList().sorted())).append(",\n")
            append("  \"card_id_collisions_with_known_regressions\": ")
            append(EvalReport.arr(idCollisions.toList().sorted())).append(",\n")
            append("  \"person_name_collisions_with_visible_roster\": ")
            append(EvalReport.arr(rosterNameCollisions.toList().sorted())).append(",\n")
            append("  \"card_id_collisions_with_visible_roster\": ")
            append(EvalReport.arr(rosterIdCollisions.toList().sorted())).append(",\n")
            append("  \"distinct_template_signatures\": ")
            append(specs.map { it.templateSignature() }.toSet().size).append(",\n")
            append("  \"template_signatures_shared_with_visible\": ")
            append(specs.count { it.templateSignature() in visibleTemplates }).append(",\n")
            append("  \"by_primary_category\": ").append(counts(byCategory.toSortedMap())).append(",\n")
            append("  \"tools_covered\": ").append(EvalReport.arr(allTools.toList().sorted())).append(",\n")
            append("  \"tools_missing\": ")
            append(EvalReport.arr((requiredTools - allTools).toList().sorted())).append(",\n")
            append("  \"distinct_tool_chains\": ")
            append(EvalReport.arr(chains.toSet().toList().sorted())).append(",\n")
            append("  \"multi_tool_turns\": ").append(multiToolTurns).append(",\n")
            append("  \"positive_cases\": ").append(positives).append(",\n")
            append("  \"negative_cases\": ").append(negatives).append(",\n")
            append("  \"user_turn_min\": ").append(turnCounts.min()).append(",\n")
            append("  \"user_turn_max\": ").append(turnCounts.max()).append(",\n")
            append("  \"user_turn_buckets\": ").append(counts(lengthBuckets.toSortedMap())).append(",\n")
            append("  \"problems\": ").append(EvalReport.arr(problems)).append("\n")
            append("}\n")
        }
        EvalReport.write("frozen_heldout/heldout_validation.json", json)
        EvalReport.write("frozen_heldout/heldout_cases.json", casesJson())

        assertTrue("validation problems: $problems", problems.isEmpty())
        assertEquals("exact script overlap with visible", emptyList<String>(), exactScriptOverlap)
        assertEquals(
            "normalized script overlap with visible", emptyList<String>(), normalizedScriptOverlap,
        )
        assertEquals("exact utterance overlap with visible", emptyList<String>(), utteranceOverlap)
        assertEquals(
            "normalized utterance overlap with visible",
            emptyList<String>(), normalizedUtteranceOverlap,
        )
        assertEquals("internal exact duplicates", emptyList<String>(), internalExact)
        assertEquals("internal normalized duplicates", emptyList<String>(), internalNormalized)
        assertEquals("name-only template clones", emptyList<String>(), nameOnlyClones)
        assertEquals("names shared with known regressions", emptySet<String>(), nameCollisions)
        assertEquals("ids shared with known regressions", emptySet<String>(), idCollisions)
        assertEquals("names shared with visible roster", emptySet<String>(), rosterNameCollisions)
        assertEquals("ids shared with visible roster", emptySet<String>(), rosterIdCollisions)

        assertTrue("held-out needs >= 72 cases, got ${specs.size}", specs.size >= 72)
        assertEquals(
            "every primary category must appear",
            EvalCategories.MINIMUMS.keys, byCategory.keys,
        )
        assertEquals("all six tools must appear", emptySet<String>(), requiredTools - allTools)
        assertTrue("no positive cases", positives > 0)
        assertTrue("no negative cases", negatives > 0)
        assertTrue("shortest conversation must be >= 1 turn", turnCounts.min() >= 1)
        assertTrue("needs a conversation of 20+ user turns", turnCounts.max() >= 20)
        assertTrue("needs 2-turn conversations", turnCounts.count { it == 2 } > 0)
    }

    private fun bucket(turns: Int): String = when {
        turns <= 1 -> "1"
        turns <= 2 -> "2"
        turns <= 5 -> "3-5"
        turns <= 10 -> "6-10"
        turns <= 20 -> "11-20"
        else -> "21+"
    }

    private fun counts(values: Map<String, Int>): String =
        values.entries.joinToString(", ", "{", "}") { "${EvalReport.q(it.key)}: ${it.value}" }

    /** The dataset itself, dumped so the scored run and the report read the same declaration. */
    private fun casesJson(): String = buildString {
        append("{\n")
        append("  \"suite\": \"frozen_heldout\",\n")
        append("  \"seed\": ").append(FrozenHeldoutCases.SEED).append(",\n")
        append("  \"provenance_of_dataset\": ").append(
            EvalReport.q(
                "post-implementation frozen held-out; production source locked before dataset " +
                    "generation. Not independently blinded.",
            ),
        ).append(",\n")
        append("  \"total\": ").append(cases.size).append(",\n")
        append("  \"cards\": [\n")
        append(
            FrozenHeldoutCases.ALL_CARDS.joinToString(",\n") { card ->
                "    {${EvalReport.q("id")}: ${EvalReport.q(card.id)}, " +
                    "${EvalReport.q("name")}: ${EvalReport.q(card.name)}, " +
                    "${EvalReport.q("company")}: ${EvalReport.q(card.company)}, " +
                    "${EvalReport.q("title")}: ${EvalReport.q(card.title)}, " +
                    "${EvalReport.q("industry")}: ${EvalReport.q(card.industry)}, " +
                    "${EvalReport.q("email")}: ${card.email?.let(EvalReport::q) ?: "null"}, " +
                    "${EvalReport.q("mobile")}: ${card.mobile?.let(EvalReport::q) ?: "null"}}"
            },
        )
        append("\n  ],\n")
        append("  \"cases\": [\n")
        append(cases.joinToString(",\n") { case -> caseJson(case) })
        append("\n  ]\n}\n")
    }

    private fun caseJson(case: FrozenHeldoutCases.HeldoutCase): String = buildString {
        val spec = case.spec
        append("    {")
        append(EvalReport.q("id")).append(": ").append(EvalReport.q(spec.id)).append(", ")
        append(EvalReport.q("primary_category")).append(": ")
        append(EvalReport.q(spec.primaryCategory)).append(", ")
        append(EvalReport.q("secondary_tags")).append(": ")
        append(EvalReport.arr(spec.secondaryTags)).append(", ")
        append(EvalReport.q("polarity")).append(": ").append(EvalReport.q(case.polarity)).append(", ")
        append(EvalReport.q("provenance")).append(": ")
        append(EvalReport.q(case.provenance)).append(", ")
        append(EvalReport.q("note")).append(": ").append(EvalReport.q(case.note)).append(", ")
        append(EvalReport.q("user_turn_count")).append(": ").append(spec.userTurnCount).append(", ")
        append(EvalReport.q("template_signature")).append(": ")
        append(EvalReport.q(spec.templateSignature())).append(", ")
        append(EvalReport.q("cards")).append(": ")
        append(EvalReport.arr(spec.cards.map { it.id })).append(", ")
        append(EvalReport.q("search_backend_failure")).append(": ").append(spec.searchFailure)
        append(", ")
        append(EvalReport.q("update_confirmed")).append(": ").append(spec.confirmUpdates).append(", ")
        append(EvalReport.q("forbidden_values")).append(": ")
        append(EvalReport.arr(spec.forbiddenValues)).append(", ")
        append(EvalReport.q("expected_side_effects_total")).append(": ")
        append(spec.turns.sumOf { it.expectedSideEffects }).append(", ")
        append(EvalReport.q("turns")).append(": [")
        append(
            spec.turns.joinToString(", ") { turn ->
                buildString {
                    append("{")
                    append(EvalReport.q("user")).append(": ").append(EvalReport.q(turn.user))
                    append(", ").append(EvalReport.q("reset_before")).append(": ")
                    append(turn.resetBefore)
                    append(", ").append(EvalReport.q("expected_route")).append(": ")
                    append(turn.expectedAct?.name?.let(EvalReport::q) ?: "null")
                    append(", ").append(EvalReport.q("expected_outcome")).append(": ")
                    append(turn.expectedOutcome?.name?.let(EvalReport::q) ?: "null")
                    append(", ").append(EvalReport.q("expected_tool_trace")).append(": ")
                    append(EvalReport.arr(turn.expectedTools))
                    append(", ").append(EvalReport.q("forbidden_tools")).append(": ")
                    append(EvalReport.arr(turn.forbiddenTools.toList().sorted()))
                    append(", ").append(EvalReport.q("expected_args")).append(": {")
                    append(
                        turn.expectedArgs.entries.joinToString(", ") { (tool, args) ->
                            "${EvalReport.q(tool)}: " +
                                args.entries.joinToString(", ", "{", "}") { (k, v) ->
                                    "${EvalReport.q(k)}: ${EvalReport.q(v)}"
                                }
                        },
                    )
                    append("}")
                    append(", ").append(EvalReport.q("expected_selected_card_id")).append(": ")
                    append(turn.expectedSelectedCardId?.let(EvalReport::q) ?: "null")
                    append(", ").append(EvalReport.q("expect_no_selected_contact")).append(": ")
                    append(turn.expectNoSelectedContact)
                    append(", ").append(EvalReport.q("expected_selected_not_card_id")).append(": ")
                    append(turn.expectedSelectedNotCardId?.let(EvalReport::q) ?: "null")
                    append(", ").append(EvalReport.q("expected_candidate_ids")).append(": ")
                    append(turn.expectedCandidateIds?.let { EvalReport.arr(it) } ?: "null")
                    append(", ").append(EvalReport.q("expected_side_effects")).append(": ")
                    append(turn.expectedSideEffects)
                    append(", ").append(EvalReport.q("expected_compose_to")).append(": ")
                    append(turn.expectedComposeTo?.let(EvalReport::q) ?: "null")
                    append(", ").append(EvalReport.q("answer_must_contain")).append(": ")
                    append(EvalReport.arr(turn.answerMustContain))
                    append(", ").append(EvalReport.q("answer_must_not_contain")).append(": ")
                    append(EvalReport.arr(turn.answerMustNotContain))
                    append("}")
                }
            },
        )
        append("]}")
    }
}
