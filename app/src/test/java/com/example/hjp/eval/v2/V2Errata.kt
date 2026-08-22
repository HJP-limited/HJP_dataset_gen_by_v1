package com.example.hjp.eval.v2

/**
 * The repair for the four self-contradictory v2 scenarios, kept apart from the frozen fixture.
 *
 * ## What is wrong
 *
 * `V2Scenario.forbiddenValues` is checked scenario-wide against every draft the run produced. Four
 * scenarios declare a contact's email forbidden *and* contain an earlier turn whose own expectation
 * is a compose to that same address. No behaviour satisfies both halves, so the case scores nothing
 * about the agent — see [DatasetConsistency].
 *
 * ## Why the intended meaning is unique
 *
 * The four are drawn from one family. `v2_focus_compose_then_bare_calendar`,
 * `v2_focus_update_then_bare_calendar` and `v2_focus_detail_then_bare_calendar` share a shape: put a
 * contact in focus, then issue an attendee-less schedule, and check the schedule does not silently
 * inherit that contact. The last two are consistent; the first is not. The only difference is what
 * puts the contact in focus — a compose *uses* the address, a memo edit and a detail read do not.
 * So `forbiddenValues` was written to guard the turn under test, and it only became a contradiction
 * in the variants whose setup turn legitimately uses the value.
 *
 * That gives a repair that is forced rather than chosen: **a value is forbidden in the turns that do
 * not themselves require it.** The rule is not a special case for four ids —
 *
 *  - on every scenario with no contradiction it accepts and rejects exactly what the original rule
 *    did, because there the value is required by no turn at all (asserted by
 *    `HeldoutV2CorrectedRunnerTest`), and
 *  - on the four contradictory ones it keeps the whole point of the assertion: the address may
 *    appear in the turn that asks for it, and must not appear anywhere else.
 *
 * Anything weaker (drop `forbiddenValues`, drop the scenarios) discards the safety check the case
 * was written for. Anything stronger is the contradiction again.
 *
 * ## What this is not
 *
 * The frozen v2 fixture is not edited and the official v2 result is not restated. This is a v2.1
 * corrective evaluation carried out beside the original, which stays on record as `DATASET_INVALID`.
 */
object V2Errata {

    const val VERSION = "v2.1"

    /** A scenario the errata covers, and the reasoning that made the repair unique. */
    data class Entry(
        val scenarioId: String,
        val defect: String,
        val repair: String,
        val evidence: String,
    )

    /**
     * Values a turn is itself required to produce, and so may legitimately use.
     *
     * Read off the turn's own expectations — the same fields the evaluator scores — so the exemption
     * can never be broader than what the dataset already demands of that turn.
     */
    fun requiredValues(turn: V2Turn): Set<String> = buildSet {
        turn.composeTo?.let(::add)
        turn.calendarAttendees?.let(::addAll)
        turn.args.forEach { (_, arguments) -> addAll(arguments.values) }
    }

    /** True when [value] is forbidden in this turn under the corrected semantics. */
    fun forbiddenIn(turn: V2Turn, value: String): Boolean = value !in requiredValues(turn)

    /**
     * The corrected rule agrees with the original wherever the original was satisfiable.
     *
     * A scenario is unaffected when no turn requires a forbidden value: then `forbiddenIn` is true
     * for every turn and the scoped check is the scenario-wide check.
     */
    fun agreesWithOriginal(scenario: V2Scenario): Boolean =
        scenario.forbiddenValues.none { value -> scenario.turns.any { !forbiddenIn(it, value) } }

    fun entries(scenarios: List<V2Scenario>): List<Entry> =
        DatasetConsistency.invalid(scenarios).keys.sorted().map { id ->
            val scenario = scenarios.first { it.id == id }
            val clashing = scenario.forbiddenValues.filter { value ->
                scenario.turns.any { !forbiddenIn(it, value) }
            }
            val usingTurns = clashing.flatMap { value ->
                scenario.turns.mapIndexedNotNull { index, turn ->
                    if (!forbiddenIn(turn, value)) index else null
                }
            }.distinct().sorted()
            Entry(
                scenarioId = id,
                defect = "scenario-wide forbiddenValues declares $clashing, which turn(s) " +
                    "$usingTurns are themselves required to produce",
                repair = "scope the forbidden value to the turns that do not require it; the " +
                    "value stays forbidden in every other turn of the scenario",
                evidence = "sibling scenarios of the same family that do not use the value in " +
                    "their setup turn are unaffected by this rule, so the rule restates the " +
                    "author's intent rather than relaxing it",
            )
        }
}
