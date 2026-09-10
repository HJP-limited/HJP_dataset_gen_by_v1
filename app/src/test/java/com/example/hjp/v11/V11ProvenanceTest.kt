package com.example.hjp.v11

import com.example.hjp.v11.V11Contracts.ENUMERATION_SCAN
import com.example.hjp.v11.V11Contracts.GENERATED_IDENTITY
import com.example.hjp.v11.V11Contracts.RUN_STATE_WITNESS
import com.example.hjp.v11.V11Contracts.SOURCE_IDENTITY
import com.example.hjp.v11.V11Contracts.STAGE_OUTPUT_WITNESS
import com.example.hjp.v11.V11Contracts.V10_VERDICT
import com.example.hjp.v11.V11Contracts.V7_FAILING_SOURCE
import com.example.hjp.v11.V11Contracts.at
import com.example.hjp.v11.V11Contracts.bool
import com.example.hjp.v11.V11Contracts.int
import com.example.hjp.v11.V11Contracts.list
import com.example.hjp.v11.V11Contracts.present
import com.example.hjp.v11.V11Contracts.requireObj
import com.example.hjp.v11.V11Contracts.sha256
import com.example.hjp.v11.V11Contracts.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What v11 carried, what it did not touch, and what it declines to claim. */
class V11ProvenanceTest {

    @Test
    fun `the four readers are byte-identical to v9's, through v10`() {
        val differences = listOf("score_run", "recompute_run", "compare_readers", "validate_run")
            .mapNotNull { stem ->
                val nine = sha256("tools/ryeong_official_v9/${stem}_v9.py")
                val ten = sha256("tools/ryeong_official_v10/${stem}_v10.py")
                val eleven = sha256("tools/ryeong_official_v11/${stem}_v11.py")
                assertNotNull("$stem must exist in v9", nine)
                assertNotNull("$stem must exist in v11", eleven)
                if (nine == ten && ten == eleven) null else "$stem: $nine / $ten / $eleven"
            }
        assertEquals("a changed reader makes a difference between runs unattributable",
                     emptyList<String>(), differences)
    }

    @Test
    fun `the cross-run reader is the same file in v7 through v11`() {
        val digests = listOf("v7", "v8", "v9", "v10", "v11")
            .map { sha256("tools/ryeong_official_$it/cross_run_reader.py") }
        digests.forEach { assertNotNull("every version's reader must be present", it) }
        assertEquals("five versions, one reader", 1, digests.distinct().size)
    }

    @Test
    fun `the generated identity has two byte-identical destinations`() {
        assertEquals("one render, two destinations", sha256(GENERATED_IDENTITY),
                     sha256(SOURCE_IDENTITY))
    }

    @Test
    fun `v10's verdict and the frozen v7 failure are untouched`() {
        assertEquals("HOLD BEFORE V10 DEVICE PREFLIGHT",
                     str(requireObj(V10_VERDICT)["version_level_verdict"]))
        assertEquals(27, int(requireObj(V10_VERDICT)["gates_passed"]))
        assertTrue("the v7 characterization suite must still be in the tree",
                   present(V7_FAILING_SOURCE))
    }

    @Test
    fun `both v10 witnesses are read-only and conclusive`() {
        listOf(RUN_STATE_WITNESS to "V10 FROZEN RUN STATE WITNESSED",
               STAGE_OUTPUT_WITNESS to "V10 STAGE OUTPUT LIFECYCLE WITNESSED").forEach {
            (path, verdict) ->
            val witness = requireObj(path)
            assertEquals(verdict, str(witness["verdict"]))
            assertEquals(listOf("NOT_OFFICIAL_V10_REJUDGMENT", "READ_ONLY_V11_WITNESS"),
                         list(witness["status"]))
            assertEquals("reading must have changed nothing", true,
                         bool(witness["every_input_unchanged_by_this_read"]))
        }
    }

    @Test
    fun `no v11 operational consumer holds a manual run list`() {
        val scan = requireObj(ENUMERATION_SCAN)
        assertEquals("NO OPERATIONAL MANUAL ENUMERATION", str(scan["verdict"]))
        assertEquals(0, int(scan["operational_manual_enumeration_count"]))
        // The scan must actually be reaching v11 files, or it is passing for the wrong reason.
        val classified = list(at(scan, "paths_by_classification/CHARACTERIZATION_ORACLE"))
        assertTrue("the scan must classify v11 paths, not sweep them into frozen history",
                   classified.any { it.contains("/v11") })
    }
}
