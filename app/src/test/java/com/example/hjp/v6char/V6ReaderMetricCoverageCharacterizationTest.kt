package com.example.hjp.v6char

import com.example.hjp.eval.ryeong.RyeongCards
import com.example.hjp.eval.ryeong2.EvidenceRoot
import com.example.hjp.eval.ryeong2.RyeongV2ScenarioLoader
import com.example.hjp.eval.v4.ToolSemanticCompletion
import com.example.hjp.eval.v5.V5ContactDependencyContract
import com.example.hjp.eval.v5.V5ContactStore
import com.example.hjp.eval.v6.RyeongV6Recomputation
import com.example.hjp.eval.v6.RyeongV6Scorer
import com.example.hjp.eval.v6.V6Comparison
import com.example.hjp.eval.v6.V6Contract
import com.example.hjp.eval.v6.V6DatasetLoader
import com.example.hjp.eval.v6.V6Digest
import com.example.hjp.eval.v6.V6RawReader
import com.example.hjp.eval.v6.V6Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every reader computes every official metric, and the comparison visits every one of them.
 *
 * v5's independent Kotlin recomputation reported `"agrees": true` while computing four of the
 * fifteen metrics the run publishes. Its Python scorer computed five. Their comparison table listed
 * sixteen field pairs and JGA was not among them. Each of those is true on its own and none of them
 * is visible from inside the reader that has the gap — which is why coverage is asserted here, from
 * the outside, against the contract's own list.
 *
 * A reader is allowed to be wrong. It is not allowed to be silent.
 */
class V6ReaderMetricCoverageCharacterizationTest {

    private val k5Raw =
        "integration_evidence/evaluation/ryeong_official_v5/result/jvm_keyword/raw_turns.jsonl"

    @Test
    fun `the kotlin official evaluator computes every official metric`() {
        assertComplete("kotlin_official_evaluator", official())
    }

    @Test
    fun `the kotlin independent recomputation computes every official metric`() {
        assertComplete("kotlin_independent_recomputation", independent())
    }

    @Test
    fun `every metric's census partitions the population it claims to cover`() {
        val report = official()
        val problems = report.metrics.values.mapNotNull { metric ->
            val expected = when (metric.unit) {
                "turn" -> report.turnCount
                "scenario" -> report.scenarioCount
                // A search-invocation metric's population is the run's invocations, which only the
                // two coverage metrics see. They are cross-checked against each other below.
                else -> metric.census.total
            }
            if (metric.census.total == expected) {
                null
            } else {
                "${metric.name}: census total ${metric.census.total} != ${metric.unit} " +
                    "population $expected"
            }
        }
        assertEquals(
            "a census that does not partition its population is not a census:\n" +
                problems.joinToString("\n"),
            emptyList<String>(), problems,
        )
    }

    @Test
    fun `every metric's scored key count equals its denominator`() {
        val report = official()
        val problems = report.metrics.values.mapNotNull { metric ->
            val scored = metric.keys["scored"].orEmpty().size
            val declared = if (metric.kind == "mean") metric.count else metric.denominator
            if (scored == declared) {
                null
            } else {
                "${metric.name}: $scored scored keys but a denominator of $declared"
            }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `every metric's passed and failed key sets partition its scored key set`() {
        val report = official()
        val problems = report.metrics.values.mapNotNull { metric ->
            if (metric.kind == "mean") return@mapNotNull null
            val scored = metric.keys["scored"].orEmpty().toSet()
            val passed = metric.keys["passed"].orEmpty().toSet()
            val failed = metric.keys["failed"].orEmpty().toSet()
            when {
                passed.intersect(failed).isNotEmpty() ->
                    "${metric.name}: a key is both passed and failed"
                passed + failed != scored ->
                    "${metric.name}: passed union failed is not the scored set"
                passed.size != metric.numerator ->
                    "${metric.name}: ${passed.size} passed keys but a numerator of ${metric.numerator}"
                else -> null
            }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `the two coverage metrics share one population and partition it`() {
        val report = official()
        val keyword = report.metrics.getValue("keyword_coverage")
        val semantic = report.metrics.getValue("semantic_coverage")
        assertEquals(
            "keyword and semantic coverage must be measured over the same invocations",
            keyword.keys["scored"].orEmpty().sorted(), semantic.keys["scored"].orEmpty().sorted(),
        )
        assertEquals("the two coverage censuses must be identical", keyword.census, semantic.census)
        assertEquals(
            "every search invocation is either keyword or semantic and never both",
            keyword.denominator, keyword.numerator!! + semantic.numerator!!,
        )
    }

    @Test
    fun `the per-kind and per-depth partitions sum to the metric they partition`() {
        val report = official()
        val strict = report.metrics.getValue("route_accuracy_strict")
        listOf(
            "route_strict_by_kind",
            "route_strict_by_scenario_turn_count",
            "route_strict_by_turn_depth",
        ).forEach { name ->
            val partition = report.metrics.getValue(name)
            assertEquals(
                "$name numerators must sum to route_accuracy_strict",
                strict.numerator, partition.entries.values.sumOf { it.first },
            )
            assertEquals(
                "$name denominators must sum to route_accuracy_strict",
                strict.denominator, partition.entries.values.sumOf { it.second },
            )
        }
    }

    @Test
    fun `the comparison visits every metric the contract declares`() {
        val report = official()
        val result = V6Comparison.compare(listOf(report, report))
        assertEquals(
            "the comparison's metric list is not the contract's",
            V6Contract.OFFICIAL_METRICS, result.metricsCompared,
        )
        assertTrue(
            "a reader compared against itself must agree: ${result.disagreements.map { it.render() }}",
            result.agrees,
        )
    }

    @Test
    fun `the restated metric table does not drift from the declared one`() {
        val drift = V6Contract.drift()
        assertEquals(
            "the Kotlin metric table and metric_scoring_contract.json disagree:\n" +
                drift.joinToString("\n"),
            emptyList<String>(), drift,
        )
    }

    private fun assertComplete(reader: String, report: V6Report) {
        val absent = V6Contract.OFFICIAL_METRICS.filterNot { report.metrics.containsKey(it) }
        assertEquals(
            "$reader does not compute: ${absent.joinToString(", ")}",
            emptyList<String>(), absent,
        )
        assertEquals(
            "$reader declares metrics it did not compute: " +
                report.metricsNotComputed.joinToString(", "),
            emptyList<String>(), report.metricsNotComputed,
        )
    }

    private fun official(): V6Report = RyeongV6Scorer.score(
        dataset = V6DatasetLoader.load(),
        record = V6RawReader.read(EvidenceRoot.file(k5Raw).readText(Charsets.UTF_8)),
        store = V5ContactStore.fromRecords(RyeongCards.load()),
        contract = V5ContactDependencyContract.load(),
        semantics = ToolSemanticCompletion.load(),
        datasetSha256 = datasetSha(),
        fixtureSha256 = fixtureSha(),
        rawSha256 = rawSha(),
    )

    private fun independent(): V6Report = RyeongV6Recomputation.fromRaw(
        rawFile = EvidenceRoot.file(k5Raw),
        datasetRaw = RyeongV2ScenarioLoader.loadRaw(),
        fixtureRaw = RyeongCards.loadRaw(),
        datasetSha256 = datasetSha(),
        fixtureSha256 = fixtureSha(),
        rawSha256 = rawSha(),
    )

    private fun datasetSha() =
        V6Digest.ofBytes(RyeongV2ScenarioLoader.loadRaw().toByteArray(Charsets.UTF_8))

    private fun fixtureSha() = V6Digest.ofBytes(RyeongCards.loadRaw().toByteArray(Charsets.UTF_8))

    private fun rawSha() = V6Digest.ofBytes(EvidenceRoot.file(k5Raw).readBytes())
}
