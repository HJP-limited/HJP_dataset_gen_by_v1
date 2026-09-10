package com.example.hjp.v7

// Generated from app/src/test/java/com/example/hjp/v6/V6DeviceRecordReadTest.kt by
// tools/ryeong_official_v7/retarget_host_runner_v7.py's sibling substitution list, recorded in
// integration_evidence/evaluation/ryeong_official_v7/provenance/DEVICE_READER_RETARGET.json.
// The two Kotlin readers are the frozen v6 ones; only the opt-in names, the package and the
// tool paths are v7's.

import com.example.hjp.eval.ryeong.RyeongCards
import com.example.hjp.eval.ryeong2.EvidenceRoot
import com.example.hjp.eval.ryeong2.RyeongV2ScenarioLoader
import com.example.hjp.eval.v4.ToolSemanticCompletion
import com.example.hjp.eval.v5.V5ContactDependencyContract
import com.example.hjp.eval.v5.V5ContactStore
import com.example.hjp.eval.v6.RyeongV6Recomputation
import com.example.hjp.eval.v6.RyeongV6Scorer
import com.example.hjp.eval.v6.V6Comparison
import com.example.hjp.eval.v6.V6DatasetLoader
import com.example.hjp.eval.v6.V6Digest
import com.example.hjp.eval.v6.V6RawReader
import com.example.hjp.eval.v6.V6ReportJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Kotlin half of Phase B's four-reader verification, run on the host over a pulled device
 * record.
 *
 * ## Why this is a host test
 *
 * The device produced the record; it must not also be the thing that grades it. So the two Kotlin
 * readers run here, on a machine, over bytes that have already been pulled and digest-checked
 * against the device copy. The two Python readers run beside them from the shell, and
 * `compare_readers_v7.py` compares all four.
 *
 * ## Opt-in
 *
 * Without `RYEONG_V7_DEVICE_RAW` this reports as skipped, so the ordinary aggregate never tries to
 * read a device record that does not exist. The skip is declared in
 * `tools/ryeong_official_v7/aggregate_jvm_v7.py`.
 *
 * Running it is not a device operation: it reads a file.
 */
class V7DeviceRecordReadTest {

    private val rawPath: String? = System.getenv("RYEONG_V7_DEVICE_RAW")
        ?: System.getProperty("ryeongV7DeviceRaw")

    private val outPath: String? = System.getenv("RYEONG_V7_DEVICE_OUT")
        ?: System.getProperty("ryeongV7DeviceOut")

    @Test
    fun `the two kotlin readers read a pulled device record`() {
        assumeTrue("no device record supplied", rawPath != null && outPath != null)

        val file = File(rawPath!!)
        assertTrue("the pulled device record is missing: $rawPath", file.isFile)
        val out = File(outPath!!).apply { mkdirs() }

        val datasetRaw = RyeongV2ScenarioLoader.loadRaw()
        val fixtureRaw = RyeongCards.loadRaw()
        val datasetSha = V6Digest.ofBytes(datasetRaw.toByteArray(Charsets.UTF_8))
        val fixtureSha = V6Digest.ofBytes(fixtureRaw.toByteArray(Charsets.UTF_8))
        val rawSha = V6Digest.ofBytes(file.readBytes())
        val rawText = file.readText(Charsets.UTF_8)
        val record = V6RawReader.read(rawText)

        assertEquals(
            "a device record scored by the frozen v6 readers must be the v6 schema",
            V6RawReader.V6_SCHEMA, record.schema,
        )

        val official = RyeongV6Scorer.score(
            dataset = V6DatasetLoader.parse(datasetRaw),
            record = record,
            store = V5ContactStore.fromRecords(RyeongCards.load()),
            contract = V5ContactDependencyContract.load(),
            semantics = ToolSemanticCompletion.load(),
            datasetSha256 = datasetSha,
            fixtureSha256 = fixtureSha,
            rawSha256 = rawSha,
        )
        val independent = RyeongV6Recomputation.fromText(
            rawText = rawText,
            datasetRaw = datasetRaw,
            fixtureRaw = fixtureRaw,
            datasetSha256 = datasetSha,
            fixtureSha256 = fixtureSha,
            rawSha256 = rawSha,
        )

        File(out, "KOTLIN_OFFICIAL_EVALUATOR_REPORT.json")
            .writeText(V6ReportJson.write(official) + "\n", Charsets.UTF_8)
        File(out, "KOTLIN_RECOMPUTATION_REPORT.json")
            .writeText(V6ReportJson.write(independent) + "\n", Charsets.UTF_8)

        val comparison = V6Comparison.compare(listOf(official, independent))
        File(out, "KOTLIN_READER_COMPARISON.json")
            .writeText(V6Comparison.toJson(comparison) + "\n", Charsets.UTF_8)

        assertEquals(
            "the official evaluator did not compute every metric",
            emptyList<String>(), official.metricsNotComputed,
        )
        assertEquals(
            "the independent recomputation did not compute every metric",
            emptyList<String>(), independent.metricsNotComputed,
        )
        assertEquals(
            "the two Kotlin readers disagree about the device record:\n" +
                comparison.disagreements.joinToString("\n") { it.render() },
            emptyList<String>(), comparison.disagreements.map { it.render() },
        )
        assertEquals(
            "a v6 device record must support the exact session-reset derivation",
            RyeongV6Scorer.STRENGTH_EXACT,
            official.metrics.getValue("session_reset_correctness").notes["derivation_strength"],
        )
    }

    @Test
    fun `the reader is wired to the repository the freeze pins`() {
        // Cheap, and it runs whether or not a device record was supplied: a Phase B operator who
        // discovers the dataset moved wants to know before the device is unplugged.
        assertEquals(
            "the frozen scenario manifest",
            "2d86ce0a1ca8be195554cb0292f48a0fb1d39b892f88a51295caff7943cdf685",
            V6Digest.ofBytes(RyeongV2ScenarioLoader.loadRaw().toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            "the frozen card fixture",
            "f0feaebfdf5eb26c2a161a4b8c40d1307a6f5fa9c68f00309f05b69d03e7cd24",
            V6Digest.ofBytes(RyeongCards.loadRaw().toByteArray(Charsets.UTF_8)),
        )
        assertTrue(
            "the metric contract must be readable from the repository root",
            EvidenceRoot.file("tools/ryeong_official_v7/contracts/metric_scoring_contract.json")
                .isFile,
        )
    }
}
