package com.example.hjp.eval.v2

/**
 * Finds cases that no behaviour could satisfy.
 *
 * Four v2 scenarios require a compose to an address *and* declare that same address in a
 * scenario-wide forbidden list. There is no agent behaviour that satisfies both halves, so scoring
 * them tells you nothing about the agent: a pass would be impossible and a failure is not a defect.
 * They were being counted as production failures, which is how a self-contradictory fixture came to
 * sit in a release gate.
 *
 * The rule is general — required∩forbidden over values, tools, card ids and arguments — not a list of
 * the four scenario ids. The original fixture is never edited; a contradictory case is reported as
 * `DATASET_INVALID`, excluded from the scored denominator, and counted out loud.
 */
object DatasetConsistency {

    /** One way a case contradicts itself, in terms a reader can act on. */
    data class Contradiction(
        val kind: String,
        val detail: String,
        /** -1 for a scenario-wide declaration. */
        val turn: Int,
    )

    fun contradictions(scenario: V2Scenario): List<Contradiction> {
        val found = mutableListOf<Contradiction>()

        // Everything the scenario declares it must produce.
        val requiredValues = mutableSetOf<String>()
        val requiredCardIds = mutableSetOf<String>()
        scenario.turns.forEachIndexed { index, turn ->
            turn.composeTo?.let(requiredValues::add)
            turn.calendarAttendees?.let(requiredValues::addAll)
            turn.args.forEach { (_, kv) ->
                kv.forEach { (key, value) ->
                    requiredValues += value
                    if (key == "card_id") requiredCardIds += value
                }
            }
            turn.selectedCardId?.let(requiredCardIds::add)

            // Within one turn: a tool both expected and forbidden.
            val toolClash = turn.tools.toSet() intersect turn.forbidden
            if (toolClash.isNotEmpty()) {
                found += Contradiction(
                    "required_and_forbidden_tool",
                    "turn $index both expects and forbids $toolClash",
                    index,
                )
            }
            // Within one turn: a side effect required while its tool is forbidden.
            if (turn.sideEffects > 0 && turn.tools.none { it in V2Tools.SIDE_EFFECTING }) {
                found += Contradiction(
                    "side_effect_without_tool",
                    "turn $index expects ${turn.sideEffects} side effects but no " +
                        "side-effecting tool",
                    index,
                )
            }
            // Within one turn: a target both required and excluded.
            if (turn.expectNoSelected && turn.selectedCardId != null) {
                found += Contradiction(
                    "required_and_forbidden_target",
                    "turn $index expects target ${turn.selectedCardId} and also expects none",
                    index,
                )
            }
        }

        // Scenario-wide: a value the scenario is required to use and forbidden to use.
        val valueClash = scenario.forbiddenValues.toSet() intersect requiredValues
        if (valueClash.isNotEmpty()) {
            found += Contradiction(
                "required_and_forbidden_value",
                "scenario-wide forbiddenValues names $valueClash, which the scenario's own " +
                    "expectations require it to use",
                -1,
            )
        }
        val cardClash = scenario.forbiddenValues.toSet() intersect requiredCardIds
        if (cardClash.isNotEmpty()) {
            found += Contradiction(
                "required_and_forbidden_card_id",
                "scenario-wide forbiddenValues names card id(s) $cardClash that the scenario requires",
                -1,
            )
        }
        return found
    }

    /** Every scenario that cannot be satisfied, with its reasons. */
    fun invalid(scenarios: List<V2Scenario>): Map<String, List<Contradiction>> =
        scenarios.associate { it.id to contradictions(it) }.filterValues { it.isNotEmpty() }
}
