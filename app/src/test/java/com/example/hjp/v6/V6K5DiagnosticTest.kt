package com.example.hjp.v6

import com.example.hjp.eval.ryeong.RyeongCards
import com.example.hjp.eval.ryeong2.EvidenceRoot
import com.example.hjp.eval.ryeong2.J
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
import com.example.hjp.eval.v6.V6ReportJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RUN_K5`'s frozen raw record, read by the v6 candidate readers. **NOT_OFFICIAL_K5_RESULT.**
 *
 * ## What this is and is not
 *
 * RUN_K5 was executed once, scored once and verdicted once, under the frozen v5 gate. It stays
 * `EXECUTED, COMPLETED, VALID BASELINE` with `invocations = 1`, and nothing here changes that: this
 * reads 869,193 bytes that already exist and writes its answer somewhere else entirely.
 *
 * What it is for is the question v5 could not answer — *do the v6 readers agree with each other, on
 * real data, about every metric?* Running them against a raw record whose authoritative numbers are
 * already published is the only way to find that out before RUN_K6 exists.
 *
 * ## One honest limitation, carried in the metric rather than in a footnote
 *
 * `ryeong_v5_raw_turn/v1` does not record the session state each scenario opened with. The v6 raw
 * schema does. So on this record `session_reset_correctness` is derived from the weaker predicate
 * the v5 schema *does* observe, and every reader stamps
 * `OBSERVABLE_OPENING_PREDICATE_WEAKER_THAN_SCORER` on the metric — a string that is itself
 * compared, so one reader cannot claim the strong derivation while performing the weak one.
 */
class V6K5DiagnosticTest {

    private val k5Raw =
        "integration_evidence/evaluation/ryeong_official_v5/result/jvm_keyword/raw_turns.jsonl"

    private val out =
        "integration_evidence/evaluation/ryeong_official_v6/diagnostic/k5_rescored_with_v6_candidate"

    @Test
    fun `the two kotlin readers agree about every metric on the frozen K5 raw`() {
        val file = EvidenceRoot.file(k5Raw)
        assertTrue("the frozen K5 raw record is missing", file.isFile)
        val before = V6Digest.ofBytes(file.readBytes())

        val datasetRaw = RyeongV2ScenarioLoader.loadRaw()
        val fixtureRaw = RyeongCards.loadRaw()
        val datasetSha = V6Digest.ofBytes(datasetRaw.toByteArray(Charsets.UTF_8))
        val fixtureSha = V6Digest.ofBytes(fixtureRaw.toByteArray(Charsets.UTF_8))

        val official = RyeongV6Scorer.score(
            dataset = V6DatasetLoader.parse(datasetRaw),
            record = V6RawReader.read(file.readText(Charsets.UTF_8)),
            store = V5ContactStore.fromRecords(RyeongCards.load()),
            contract = V5ContactDependencyContract.load(),
            semantics = ToolSemanticCompletion.load(),
            datasetSha256 = datasetSha,
            fixtureSha256 = fixtureSha,
            rawSha256 = before,
        )
        val independent = RyeongV6Recomputation.fromRaw(
            rawFile = file,
            datasetRaw = datasetRaw,
            fixtureRaw = fixtureRaw,
            datasetSha256 = datasetSha,
            fixtureSha256 = fixtureSha,
            rawSha256 = before,
        )

        val directory = EvidenceRoot.dir(out)
        write(File(directory, "KOTLIN_OFFICIAL_EVALUATOR_REPORT.json"), V6ReportJson.write(official))
        write(File(directory, "KOTLIN_RECOMPUTATION_REPORT.json"), V6ReportJson.write(independent))

        val comparison = V6Comparison.compare(listOf(official, independent))
        write(File(directory, "KOTLIN_READER_COMPARISON.json"), V6Comparison.toJson(comparison))

        val after = V6Digest.ofBytes(file.readBytes())
        assertEquals("reading the K5 raw must leave it byte-identical", before, after)
        assertEquals(
            "the frozen K5 raw is not the artefact this diagnostic was written against",
            "d229d6ddf4bc51f7698d6022f19c46a222ceb73f09eda28bd58c7ddf1906c6a5", after,
        )
        assertEquals(
            "the two Kotlin readers disagree:\n" +
                comparison.disagreements.joinToString("\n") { it.render() },
            emptyList<String>(), comparison.disagreements.map { it.render() },
        )
    }

    @Test
    fun `the v6 readers re-derive every published RUN_K5 number from the raw record`() {
        val report = official()
        // Not hard-coded returns: every one of these comes out of the raw record and the dataset,
        // and the RUN_K5 result.json is where the expected side comes from.
        val published = mapOf(
            "route_accuracy_strict" to (103 to 328),
            "route_accuracy_store_retrieval" to (233 to 328),
            "hit_at_5" to (230 to 230),
            "jga" to (223 to 225),
            "focus_accuracy" to (216 to 239),
            "follow_up_resolution" to (144 to 219),
            "session_reset_correctness" to (139 to 139),
            "no_card_suppression" to (10 to 10),
            "keyword_coverage" to (100 to 100),
            "semantic_coverage" to (0 to 100),
        )
        val problems = published.mapNotNull { (name, expected) ->
            val metric = report.metrics[name] ?: return@mapNotNull "$name was not computed"
            val actual = metric.numerator to metric.denominator
            if (actual == expected) null else "$name: expected $expected, recomputed $actual"
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)

        val slots = report.metrics.getValue("jga").census
        assertEquals("slot census scored", 225, slots.scored)
        assertEquals("slot census not_applicable", 98, slots.notApplicable)
        assertEquals("slot census not_scorable", 63, slots.notScorable)
        assertEquals("slot census failed_to_run", 0, slots.failedToRun)
        assertEquals("slot census contract_error", 0, slots.contractError)

        assertEquals("contact-dependency findings", emptyList<String>(), report.contactFindingKeys)
        assertEquals("R@5 sum", "230.000000000", report.metrics.getValue("r_at_5").sumRendered)
        assertEquals("MRR sum", "230.000000000", report.metrics.getValue("mrr").sumRendered)
    }

    @Test
    fun `the session reset derivation declares the strength the v5 raw schema supports`() {
        val report = official()
        assertEquals(
            "the v5 raw schema records no scenario-opening state, so the metric must say so",
            RyeongV6Scorer.STRENGTH_WEAK,
            report.metrics.getValue("session_reset_correctness").notes["derivation_strength"],
        )
    }

    @Test
    fun `every official metric is computed and the contract does not drift`() {
        val report = official()
        assertEquals(
            "metrics not computed", emptyList<String>(), report.metricsNotComputed,
        )
        assertEquals(
            "contract drift", emptyList<String>(), report.contractTableDrift,
        )
        assertEquals(
            "the contract's metric list", V6Contract.OFFICIAL_METRICS,
            V6Contract.OFFICIAL_METRICS.filter { report.metrics.containsKey(it) },
        )
    }

    private fun official(): V6Report {
        val file = EvidenceRoot.file(k5Raw)
        val datasetRaw = RyeongV2ScenarioLoader.loadRaw()
        return RyeongV6Scorer.score(
            dataset = V6DatasetLoader.parse(datasetRaw),
            record = V6RawReader.read(file.readText(Charsets.UTF_8)),
            store = V5ContactStore.fromRecords(RyeongCards.load()),
            contract = V5ContactDependencyContract.load(),
            semantics = ToolSemanticCompletion.load(),
            datasetSha256 = V6Digest.ofBytes(datasetRaw.toByteArray(Charsets.UTF_8)),
            fixtureSha256 = V6Digest.ofBytes(RyeongCards.loadRaw().toByteArray(Charsets.UTF_8)),
            rawSha256 = V6Digest.ofBytes(file.readBytes()),
        )
    }

    /**
     * Writes a report with the diagnostic status spliced in front of its own fields.
     *
     * The two objects are merged rather than nested, so the file is still exactly one
     * `ryeong_v6_metric_report/v1` and the Python comparison can read it — while every reader of
     * the file meets `NOT_OFFICIAL_K5_RESULT` before it meets a number.
     */
    private fun write(target: File, body: String) {
        requireNotNull(target.parentFile) { "an evidence file needs a directory" }.mkdirs()
        val status = J.obj(
            "status" to J.q("NOT_OFFICIAL_K5_RESULT"),
            "why" to J.q(
                "RUN_K5 was executed once, scored once and verdicted once under the frozen v5 " +
                    "gate. This file is the v6 candidate readers' answer over the same bytes, " +
                    "produced for the purpose of comparing the readers with each other.",
            ),
            "source_raw" to J.q(k5Raw),
        )
        target.writeText(status.dropLast(1) + "," + body.substring(1) + "\n", Charsets.UTF_8)
    }
}
