package com.example.hjp.v10

import com.example.hjp.v10.V10Contracts.ENUMERATION_SCAN
import com.example.hjp.v10.V10Contracts.K8_STATUS
import com.example.hjp.v10.V10Contracts.K9_RAW
import com.example.hjp.v10.V10Contracts.K9_RESULT
import com.example.hjp.v10.V10Contracts.K9_STATUS
import com.example.hjp.v10.V10Contracts.PRODUCTION_ROOTS
import com.example.hjp.v10.V10Contracts.PROVENANCE
import com.example.hjp.v10.V10Contracts.RUN_SET_WITNESS
import com.example.hjp.v10.V10Contracts.V7_FAILING_SOURCE
import com.example.hjp.v10.V10Contracts.V9_DEVIATIONS
import com.example.hjp.v10.V10Contracts.V9_VERDICT
import com.example.hjp.v10.V10Contracts.at
import com.example.hjp.v10.V10Contracts.bool
import com.example.hjp.v10.V10Contracts.file
import com.example.hjp.v10.V10Contracts.int
import com.example.hjp.v10.V10Contracts.list
import com.example.hjp.v10.V10Contracts.obj
import com.example.hjp.v10.V10Contracts.present
import com.example.hjp.v10.V10Contracts.sha256
import com.example.hjp.v10.V10Contracts.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What v10 carried, what v10 did not touch, and what v10 refuses to claim.
 *
 * The four readers are v9's, byte for byte. That is not a convenience: if the readers changed, a
 * difference between K9's numbers and K10's would be unattributable, and the whole point of running
 * K10 against an unchanged production path is that any difference has one possible cause.
 */
class V10ProvenanceTest {

    @Test
    fun `the four Python readers are byte-identical to v9's`() {
        val pairs = listOf(
            "score_run" to "the python scorer",
            "recompute_run" to "the python independent recomputation",
            "compare_readers" to "the four-reader comparison",
            "validate_run" to "the host validity decision",
        )
        val differences = mutableListOf<String>()
        pairs.forEach { (stem, why) ->
            val v9 = sha256("tools/ryeong_official_v9/${stem}_v9.py")
            val v10 = sha256("tools/ryeong_official_v10/${stem}_v10.py")
            assertNotNull("$stem must exist in v9", v9)
            assertNotNull("$stem must exist in v10", v10)
            if (v9 != v10) differences += "$stem ($why): v9=$v9 v10=$v10"
        }
        assertEquals("a changed reader makes a K9-to-K10 difference unattributable",
                     emptyList<String>(), differences)
    }

    @Test
    fun `the cross-run reader is the same file in v7, v8, v9 and v10`() {
        val digests = listOf("v7", "v8", "v9", "v10")
            .map { it to sha256("tools/ryeong_official_$it/cross_run_reader.py") }
        digests.forEach { (version, digest) ->
            assertNotNull("the $version cross-run reader must be present", digest)
        }
        assertEquals("four versions, one reader", 1, digests.map { it.second }.distinct().size)
    }

    @Test
    fun `production source is untouched`() {
        val provenance = obj(PROVENANCE)
        PRODUCTION_ROOTS.forEach { root ->
            assertTrue("$root must still be in the tree", file(root).isDirectory)
        }
        assertNotNull("the provenance record must be in the tree", provenance)
        assertEquals("v10 changed no production source", 0,
                     int(at(provenance, "totals/production_files_changed")))
    }

    @Test
    fun `the frozen v7 failure is still in the tree and still failing`() {
        assertTrue("the v7 characterization suite must not have been repaired or removed",
                   present(V7_FAILING_SOURCE))
        val provenance = obj(PROVENANCE)
        assertEquals("the v7 suite must be recorded as unchanged", true,
                     bool(at(provenance, "frozen/v7_characterization_unchanged")))
    }

    @Test
    fun `K9's authority artefacts are unchanged and v10 only read them`() {
        listOf(K9_RESULT, K9_RAW, K9_STATUS).forEach {
            assertTrue("$it must still be readable", present(it))
        }
        val provenance = obj(PROVENANCE)
        assertEquals("K9's raw record must be unchanged", true,
                     bool(at(provenance, "frozen/k9_raw_unchanged")))
        assertEquals("K9's result must be unchanged", true,
                     bool(at(provenance, "frozen/k9_result_unchanged")))
        assertEquals("v10 read K9 and wrote nothing to it", true,
                     bool(at(provenance, "frozen/k9_read_only")))
    }

    @Test
    fun `the two earlier verdicts are preserved exactly as they were written`() {
        val k8 = obj(K8_STATUS)
        assertEquals("VALID BASELINE", str(k8?.get("validity")))
        val v9Verdict = obj(V9_VERDICT)
        assertEquals("HOLD BEFORE V9 DEVICE PREFLIGHT", str(v9Verdict?.get("version_level_verdict")))
        assertEquals("VALID BASELINE",
                     str(at(v9Verdict,
                            "two_verdicts_that_must_not_overwrite_each_other/run_k9_run_local/verdict")))
        assertEquals("HOLD BEFORE V8 DEVICE PREFLIGHT",
                     str(at(v9Verdict, "k8_verdicts_preserved/version_level_verdict")))
    }

    @Test
    fun `the v9 deviation report is carried unedited`() {
        val report = obj(V9_DEVIATIONS)
        assertNotNull("v9's own deviation report must still be readable", report)
        assertEquals(3, (report?.get("deviations") as? JsonArray)?.size)
        assertTrue("v10 must not have edited v9's verdict",
                   (str(report?.get("verdict")) ?: "").contains("THREE DEVIATIONS"))
    }

    @Test
    fun `the run-set witness counted more sites than the v9 report claimed`() {
        val witness = obj(RUN_SET_WITNESS)
        assertNotNull("the witness must be in the tree", witness)
        val claimed = int(at(witness, "counting_reconciliation/v9_report_claimed_manual_lists"))
        val enumerated = int(at(witness, "counting_reconciliation/sites_this_witness_enumerates"))
        assertNotNull("the witness must record what v9 claimed", claimed)
        assertNotNull("the witness must record what it found", enumerated)
        assertTrue(
            "a hand count of a defect whose nature is that hand counts miss members should be " +
                "expected to be low; the witness found $enumerated against v9's $claimed",
            (enumerated ?: 0) > (claimed ?: 0),
        )
    }

    @Test
    fun `no v10 operational consumer holds a manual run list`() {
        val scan = obj(ENUMERATION_SCAN)
        assertNotNull("the v10 enumeration scan must be in the tree", scan)
        assertEquals(0, int(scan?.get("operational_manual_enumeration_count")))
        assertEquals("NO OPERATIONAL MANUAL ENUMERATION", str(scan?.get("verdict")))
        val classifications = (scan?.get("classification_counts") as? JsonObject)?.keys.orEmpty()
        assertTrue("the scan must classify rather than merely count; it found $classifications",
                   classifications.isNotEmpty())
    }

    @Test
    fun `nothing here claims a device result`() {
        val provenance = obj(PROVENANCE)
        val notClaimed = list(at(provenance, "not_claimed"))
        listOf("Gemma", "EmbeddingGemma", "semantic", "device", "production ready")
            .forEach { subject ->
                assertTrue("the provenance record must decline to claim $subject",
                           notClaimed.any { it.contains(subject, ignoreCase = true) })
            }
    }
}
