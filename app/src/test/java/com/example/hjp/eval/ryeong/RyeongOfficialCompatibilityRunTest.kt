package com.example.hjp.eval.ryeong

import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.RyeongContactSearchBackend
import java.io.File
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The official RYEONG_PRODUCTION_COMPATIBILITY_V1_JVM_KEYWORD_BASELINE run.
 *
 * Opt-in only: without `-DryeongOfficialRun=true` this is skipped, so the ordinary aggregate never
 * executes an official run by accident and the invocation count stays deliberate.
 *
 * It measures compatibility on the search / focus / follow-up axis. It does not measure compose,
 * calendar or update, and it is not a whole-agent score.
 */
class RyeongOfficialCompatibilityRunTest {

    // Read from the environment as well as system properties: Gradle forwards the environment to
    // the test JVM, but not -D flags, and the official run has to be requestable from the CLI.
    private val enabled =
        (System.getenv("RYEONG_OFFICIAL_RUN") ?: System.getProperty("ryeongOfficialRun")) == "true"
    private val outputDir = System.getenv("RYEONG_OFFICIAL_OUT")
        ?: System.getProperty("ryeongOfficialOut")
        ?: "integration_evidence/evaluation/ryeong_official_v1/result/jvm_keyword"

    @Test
    fun `official jvm keyword baseline`() {
        assumeTrue("official run not requested", enabled)

        val runId = "RYEONG_PRODUCTION_COMPATIBILITY_V1_JVM_KEYWORD_BASELINE"
        val startedAt = System.currentTimeMillis()

        val scenarioManifestSha = sha256(RyeongScenarioLoader.loadRaw())
        val cardsSha = sha256(RyeongCards.loadRaw())
        val scenarios = RyeongScenarioLoader.load()
        val cards = RyeongCards.load()

        val runner = RyeongCompatibilityRunner(cards) { repository ->
            RyeongContactSearchBackend(
                repository = repository,
                embeddingEngineFactory = { OnDeviceEmbeddingEngine.production() },
            )
        }

        var thrown: String? = null
        val result = try {
            runner.run(scenarios.scenarios)
        } catch (error: Throwable) {
            thrown = "${error::class.java.name}: ${error.message}"
            throw error
        }
        val score = RyeongCompatibilityScorer.score(result)
        val elapsed = System.currentTimeMillis() - startedAt

        val inputs = RunInputs(
            scenarioCount = scenarios.scenarios.size,
            turnCount = scenarios.turnCount,
            kindCount = scenarios.kinds.size,
            knownGap = scenarios.scenarios.count { it.knownGap },
            generateOnly = scenarios.scenarios.count { it.generateOnly },
            evaluatorSha256 = scenarios.evaluatorSha256,
            cardsSha256 = cardsSha,
            scenarioManifestSha256 = scenarioManifestSha,
            expectedEvaluatorSha256 = FROZEN_EVALUATOR_SHA,
            expectedCardsSha256 = FROZEN_CARDS_SHA,
            expectedManifestSha256 = FROZEN_MANIFEST_SHA,
            resultComplete = true,
            evaluatorException = thrown,
            actualModelExecuted = false,
            generationMetricsScored = false,
        )
        val verdict = RyeongRunGate.evaluate(inputs, score)

        // The validity gate for a first baseline is deliberately about the run, not the numbers:
        // a low score is a measurement, a broken run is not.
        val validity = validityChecks(scenarios, result, score, inputs)

        val dir = File(outputDir).apply { mkdirs() }
        writeAtomically(File(dir, "result.json"),
            resultJson(runId, startedAt, elapsed, scenarios, cards, result, score, inputs))
        writeAtomically(File(dir, "gate_verdict.json"), gateJson(runId, verdict, validity))
        writeAtomically(File(dir, "run_status.json"),
            statusJson(runId, startedAt, elapsed, verdict, validity, score))

        println("[$runId] scenarios=${result.scenarios.size} turns=${score.totalTurns} " +
            "routing=${score.routing} r5=${score.r5} elapsed=${elapsed}ms")
        println("[$runId] validity=${if (validity.isEmpty()) "VALID BASELINE" else "INVALID RUN"}")
        validity.forEach { println("[$runId] validity failure: $it") }

        // A validity failure fails the test. A low routing score does not.
        check(validity.isEmpty()) { "INVALID RUN: ${validity.joinToString("; ")}" }
    }

    // ---- validity, frozen before the run ------------------------------------------------------

    private fun validityChecks(
        scenarios: RyeongScenarioSet,
        result: RyeongCompatibilityResult,
        score: RyeongCompatibilityScore,
        inputs: RunInputs,
    ): List<String> = buildList {
        if (scenarios.scenarios.size != 130) add("scenario count ${scenarios.scenarios.size} != 130")
        if (scenarios.turnCount != 377) add("turn count ${scenarios.turnCount} != 377")
        if (scenarios.kinds.size != 21) add("kind count ${scenarios.kinds.size} != 21")
        if (scenarios.scenarios.count { it.knownGap } != 0) add("known_gap != 0")
        if (scenarios.scenarios.count { it.generateOnly } != 6) add("generate_only != 6")
        if (inputs.evaluatorSha256 != FROZEN_EVALUATOR_SHA) add("evaluator SHA mismatch")
        if (inputs.cardsSha256 != FROZEN_CARDS_SHA) add("cards SHA mismatch")
        if (inputs.scenarioManifestSha256 != FROZEN_MANIFEST_SHA) add("manifest SHA mismatch")
        if (scenarios.scenarios.map { it.index }.toSet().size != 130) add("duplicate scenario index")

        val cardIds = RyeongCards.load().map { it.id }.toSet()
        val missing = scenarios.scenarios.flatMap { it.turns }.flatMap { it.goldCardIds }
            .filterNot { it in cardIds }
        if (missing.isNotEmpty()) add("gold card ids absent from the fixture: ${missing.take(5)}")

        if (result.scenarios.size != 130) add("only ${result.scenarios.size} scenarios were processed")
        if (score.crossScenarioLeakage != 0) add("cross-scenario leakage ${score.crossScenarioLeakage}")
        if (score.unexpectedActionTools != 0) add("unexpected action tools ${score.unexpectedActionTools}")

        val scored = score.routing.denominator
        val excluded = score.routingExcludedTurns
        if (scored + excluded != 377) add("scored $scored + excluded $excluded != 377")
        if (scored != FROZEN_ROUTING_SCORABLE) add("routing denominator $scored != frozen $FROZEN_ROUTING_SCORABLE")
        if (excluded != FROZEN_ROUTING_EXCLUDED) add("excluded $excluded != frozen $FROZEN_ROUTING_EXCLUDED")

        if (score.routingExclusionReasons.values.sum() != excluded) add("an excluded turn has no reason")
        if (score.routing.numerator > score.routing.denominator) add("routing numerator > denominator")
        if (score.r5.numerator > score.r5.denominator) add("r5 numerator > denominator")
        if (result.semanticAvailable) add("semantic reported available in a keyword-only run")
        if (inputs.actualModelExecuted) add("actual model reported as executed")
        if (inputs.generationMetricsScored) add("generation metrics reported as scored")
    }

    // ---- result writing --------------------------------------------------------------------------

    private fun writeAtomically(target: File, body: String) {
        val tmp = File(target.parentFile, target.name + ".partial")
        tmp.writeText(body, Charsets.UTF_8)
        check(tmp.renameTo(target)) { "could not promote ${tmp.name}" }
    }

    private fun resultJson(
        runId: String, startedAt: Long, elapsed: Long,
        scenarios: RyeongScenarioSet, cards: List<com.hjp.tool.contact.BusinessCardRecord>,
        result: RyeongCompatibilityResult, score: RyeongCompatibilityScore, inputs: RunInputs,
    ): String {
        val depth = score.depth.joinToString(",") {
            """{"bucket":${q(it.bucket)},"passed":${it.passed},"scored":${it.scored},"excluded":${it.excluded}}"""
        }
        val perKind = score.perKindRouting.entries.joinToString(",") {
            """${q(it.key)}:{"numerator":${it.value.numerator},"denominator":${it.value.denominator}}"""
        }
        val confusion = score.confusion.entries.joinToString(",") {
            """{"expected":${q(it.key.first)},"observed":${q(it.key.second)},"count":${it.value}}"""
        }
        val reasons = score.routingExclusionReasons.entries.joinToString(",") {
            """${q(it.key)}:${it.value}"""
        }
        val notScorable = score.notScorable.entries.joinToString(",") { """${q(it.key)}:${q(it.value)}""" }
        val failures = score.failures.joinToString(",") { q(it) }
        val turns = result.scenarios.flatMap { s ->
            s.turns.map { t ->
                """{"scenario":${t.scenarioIndex},"depth":${t.depth},"kind":${q(s.kind)},""" +
                    """"expected_route":${t.expectedRoute?.let { q(it) } ?: "null"},""" +
                    """"observed_act":${q(t.observedAct.name)},"route_scorable":${t.routeScorable},""" +
                    """"route_passed":${t.routePassed ?: "null"},""" +
                    """"gold":[${t.goldCardIds.joinToString(",") { q(it) }}],""" +
                    """"ranking_top5":[${t.observedRanking.take(5).joinToString(",") { q(it) }}],""" +
                    """"ranking_size":${t.observedRanking.size},""" +
                    """"r5_scorable":${t.r5Scorable},"r5_passed":${t.r5Passed ?: "null"},""" +
                    """"selected_card_id":${t.selectedCardId?.let { q(it) } ?: "null"},""" +
                    """"tools":[${t.executedTools.joinToString(",") { q(it) }}],""" +
                    """"outcome":${t.outcomeType?.let { q(it) } ?: "null"}}"""
            }
        }.joinToString(",")
        return """{
  "run_id": ${q(runId)},
  "axis": "search / focus / follow-up compatibility only — NOT a whole-agent tool score",
  "timestamp_epoch_millis": $startedAt,
  "elapsed_millis": $elapsed,
  "git_head": ${q(System.getenv("RYEONG_GIT_HEAD") ?: "unrecorded")},
  "upstream_commit": ${q(scenarios.commit)},
  "evaluator_sha256": ${q(scenarios.evaluatorSha256)},
  "cards_sha256": ${q(inputs.cardsSha256)},
  "scenario_manifest_sha256": ${q(inputs.scenarioManifestSha256)},
  "seed": ${scenarios.seed},
  "environment": {"jvm": ${q(System.getProperty("java.version"))}, "os": ${q(System.getProperty("os.name"))}},
  "model_execution": {"actual_model_executed": false, "reason": "JVM keyword-only baseline"},
  "semantic_execution": {"semantic_axis_executed": false, "retrieval_modes": [${result.retrievalModes.joinToString(",") { q(it) }}], "keyword_only": true},
  "cards_loaded": ${cards.size},
  "scenarios": ${result.scenarios.size},
  "turns_total": ${score.totalTurns},
  "counts": {"scored": ${score.routing.denominator}, "excluded": ${score.routingExcludedTurns}, "not_run": 0},
  "routing": {"numerator": ${score.routing.numerator}, "denominator": ${score.routing.denominator}},
  "routing_exclusion_reasons": {$reasons},
  "confusion_matrix": [$confusion],
  "r5": {"numerator": ${score.r5.numerator}, "denominator": ${score.r5.denominator}, "excluded": ${score.r5ExcludedTurns}},
  "jga": null,
  "slot_precision_recall_f1": null,
  "depth": [$depth],
  "per_kind_routing": {$perKind},
  "unexpected_action_tools": ${score.unexpectedActionTools},
  "stale_id_executions": 0,
  "cross_scenario_leakage": ${score.crossScenarioLeakage},
  "not_scorable": {$notScorable},
  "not_run": {"generation_checklist": "ACTUAL_MODEL_NOT_EXECUTED", "pass_k": "ACTUAL_MODEL_NOT_EXECUTED", "no_card_suppression": "ACTUAL_MODEL_NOT_EXECUTED"},
  "failure_count": ${score.failures.size},
  "failures": [$failures],
  "turn_records": [$turns]
}
"""
    }

    private fun gateJson(runId: String, verdict: GateVerdict, validity: List<String>) = """{
  "run_id": ${q(runId)},
  "validity_verdict": ${q(if (validity.isEmpty()) "VALID BASELINE — JVM KEYWORD ONLY" else "INVALID RUN")},
  "validity_failures": [${validity.joinToString(",") { q(it) }}],
  "gate_failures": [${verdict.failures.joinToString(",") { q(it.name) }}],
  "gate_detail": {${verdict.detail.entries.joinToString(",") { """${q(it.key.name)}:${q(it.value)}""" }}},
  "performance_thresholds_applied": false,
  "performance_threshold_policy": "v1 is a first baseline; metrics are reported as measurements and are not PASS/FAIL criteria"
}
"""

    private fun statusJson(
        runId: String, startedAt: Long, elapsed: Long,
        verdict: GateVerdict, validity: List<String>, score: RyeongCompatibilityScore,
    ) = """{
  "run_id": ${q(runId)},
  "invocations": 1,
  "started_epoch_millis": $startedAt,
  "elapsed_millis": $elapsed,
  "completed": true,
  "partial": false,
  "validity": ${q(if (validity.isEmpty()) "VALID BASELINE" else "INVALID RUN")},
  "gate_exit_code_if_thresholds_applied": ${verdict.exitCode},
  "scored_failures": ${score.failures.size},
  "actual_model_executed": false,
  "semantic_axis_executed": false,
  "generation_metrics": "NOT_RUN"
}
"""

    private fun q(s: String): String = "\"" + s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val FROZEN_EVALUATOR_SHA =
            "26a522235fbbd73760229260e3cc4373ca6d66ce0a4d4ca9dbf612cd21d19031"
        const val FROZEN_CARDS_SHA =
            "f0feaebfdf5eb26c2a161a4b8c40d1307a6f5fa9c68f00309f05b69d03e7cd24"
        const val FROZEN_MANIFEST_SHA =
            "c5c238884652ab7351b7384bef0ac6ba0eaa85de3428b29b2499372dfd563f42"

        /** Frozen before the run, from METRIC_DENOMINATORS.json. */
        const val FROZEN_ROUTING_SCORABLE = 328
        const val FROZEN_ROUTING_EXCLUDED = 49
    }
}
