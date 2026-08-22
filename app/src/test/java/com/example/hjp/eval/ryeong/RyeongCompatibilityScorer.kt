package com.example.hjp.eval.ryeong

/** A ratio that always carries its own denominator, so a rate can never be read without one. */
data class Ratio(val numerator: Int, val denominator: Int) {
    /** Null, not 0.0, when there is nothing to divide — an empty denominator is not a score of zero. */
    val value: Double? get() = if (denominator == 0) null else numerator.toDouble() / denominator
    override fun toString(): String =
        value?.let { String.format("%.3f (%d/%d)", it, numerator, denominator) } ?: "- (0/0)"
}

data class DepthScore(val bucket: String, val passed: Int, val scored: Int, val excluded: Int)

data class RyeongCompatibilityScore(
    val routing: Ratio,
    val routingExcludedTurns: Int,
    val routingExclusionReasons: Map<String, Int>,
    val confusion: Map<Pair<String, String>, Int>,
    val r5: Ratio,
    val r5ExcludedTurns: Int,
    val depth: List<DepthScore>,
    val perKindRouting: Map<String, Ratio>,
    val unexpectedActionTools: Int,
    val crossScenarioLeakage: Int,
    val notScorable: Map<String, String>,
    val failures: List<String>,
) {
    val totalTurns: Int get() = routing.denominator + routingExcludedTurns
}

/**
 * Turns observations into the Ryeong metrics.
 *
 * Nothing here re-derives what the agent did; it only counts what the runner recorded. Turns the
 * translation contract could not map are excluded with a reason and never silently pass — an
 * excluded turn is absent from both the numerator and the denominator.
 */
object RyeongCompatibilityScorer {

    fun score(result: RyeongCompatibilityResult): RyeongCompatibilityScore {
        val turns = result.scenarios.flatMap { it.turns }

        val scorable = turns.filter { it.routeScorable }
        val routing = Ratio(scorable.count { it.routePassed == true }, scorable.size)
        val excluded = turns.filterNot { it.routeScorable }
        val reasons = excluded.groupingBy {
            it.expectedRoute?.let { route -> "route=$route" } ?: "route=<unasserted>"
        }.eachCount()

        val confusion = scorable
            .filter { it.routePassed == false }
            .groupingBy { (it.expectedRoute ?: "?") to it.observedAct.name }
            .eachCount()

        val r5Turns = turns.filter { it.r5Scorable }
        val r5 = Ratio(r5Turns.count { it.r5Passed == true }, r5Turns.size)

        val depth = listOf("depth_1", "depth_2", "depth_3_5", "depth_6_10", "depth_11_plus")
            .map { bucket ->
                val inBucket = result.scenarios.filter { it.depthBucket == bucket }
                val scored = inBucket.filter { it.routeAllPassed != null }
                DepthScore(
                    bucket = bucket,
                    passed = scored.count { it.routeAllPassed == true },
                    scored = scored.size,
                    excluded = inBucket.size - scored.size,
                )
            }

        val perKind = result.scenarios.groupBy { it.kind }.mapValues { (_, group) ->
            val kindTurns = group.flatMap { it.turns }.filter { it.routeScorable }
            Ratio(kindTurns.count { it.routePassed == true }, kindTurns.size)
        }.toSortedMap().toMap()

        val failures = buildList {
            scorable.filter { it.routePassed == false }.forEach {
                add("[${it.scenarioIndex}] depth ${it.depth} route ${it.expectedRoute} -> ${it.observedAct} : ${it.question}")
            }
            r5Turns.filter { it.r5Passed == false }.forEach {
                add("[${it.scenarioIndex}] depth ${it.depth} gold ${it.goldCardIds} not in top-5 of ${it.observedRanking.take(5)}")
            }
            turns.filter { it.unexpectedActionTools.isNotEmpty() }.forEach {
                add("[${it.scenarioIndex}] depth ${it.depth} unexpected_side_effect_tool ${it.unexpectedActionTools}")
            }
            result.leakage.filter { it.leaked }.forEach {
                add("[${it.scenarioIndex}] cross-scenario leakage: focus=${it.startedWithSelectedContact} history=${it.startedWithHistory}")
            }
        }

        return RyeongCompatibilityScore(
            routing = routing,
            routingExcludedTurns = excluded.size,
            routingExclusionReasons = reasons.toSortedMap().toMap(),
            confusion = confusion,
            r5 = r5,
            r5ExcludedTurns = turns.size - r5Turns.size,
            depth = depth,
            perKindRouting = perKind,
            unexpectedActionTools = result.unexpectedActionToolCount,
            crossScenarioLeakage = result.leakageCount,
            notScorable = NOT_SCORABLE,
            failures = failures,
        )
    }

    /** Metrics upstream defines that this run cannot produce, each with the reason. */
    val NOT_SCORABLE: Map<String, String> = mapOf(
        "jga" to "production's search tool takes one free-text query; the structured name/title/" +
            "location constraint lives in a package-private plan inside search-core with no observation seam",
        "slot_precision_recall_f1" to "same seam as jga",
        "generated_response_checklist" to "ACTUAL_MODEL_NOT_EXECUTED",
        "pass_k" to "ACTUAL_MODEL_NOT_EXECUTED",
        "no_card_suppression" to "upstream only judges this after generation; ACTUAL_MODEL_NOT_EXECUTED",
        "latency" to "upstream's own figure is desktop-server timing and is explicitly not a device metric",
    )
}
