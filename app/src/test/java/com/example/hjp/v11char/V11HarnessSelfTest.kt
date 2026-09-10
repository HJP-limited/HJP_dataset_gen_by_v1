package com.example.hjp.v11char

import com.example.hjp.v11char.V11CharacterizationFixture as F
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does the harness fail when it should?
 *
 * v10's characterization reported four cases as passing at RED that were passing because their
 * inputs were absent: `assertEquals(emptyList(), orphans)` holds trivially when there is nothing to
 * orphan. The deviation was reported honestly and the cases were still worthless at RED.
 *
 * A characterization is a claim about a subject. If the subject can vanish and the claim still
 * holds, the claim was never about the subject. So before the v11 RED inputs are frozen, this suite
 * checks the harness itself: absence must fail, an empty collection must fail, a negative control
 * must fail with the code it declares, and a positive control must pass. None of these read the v11
 * implementation, because none of them are about it.
 */
class V11HarnessSelfTest {

    // ---- absence must fail, with a code ------------------------------------------------------------

    @Test
    fun `a missing document fails with FIXTURE_INCOMPLETE rather than returning null`() {
        val error = assertThrows(F.FixtureIncomplete::class.java) {
            F.requireObject("tools/ryeong_official_v11/contracts/there_is_no_such_file.json")
        }
        assertEquals(F.FIXTURE_INCOMPLETE, error.code)
        assertTrue("the message must name the path it could not find",
                   error.message!!.contains("there_is_no_such_file.json"))
    }

    @Test
    fun `an unparseable document fails with FIXTURE_INCOMPLETE`() {
        val target = F.file("integration_evidence/evaluation/ryeong_official_v11/baseline/" +
                            "_harness_selftest_malformed.json")
        target.parentFile?.mkdirs()
        target.writeText("{ this is not json", Charsets.UTF_8)
        try {
            val error = assertThrows(F.FixtureIncomplete::class.java) {
                F.requireObject("integration_evidence/evaluation/ryeong_official_v11/baseline/" +
                                "_harness_selftest_malformed.json")
            }
            assertEquals(F.FIXTURE_INCOMPLETE, error.code)
        } finally {
            target.delete()
        }
    }

    @Test
    fun `a missing field fails rather than reading as absent-and-therefore-fine`() {
        val document = buildJsonObject { put("present", "yes") }
        val error = assertThrows(F.FixtureIncomplete::class.java) {
            F.requireString(document, "not/there")
        }
        assertEquals(F.FIXTURE_INCOMPLETE, error.code)
    }

    @Test
    fun `a field of the wrong shape fails rather than coercing`() {
        val document = buildJsonObject { put("count", "seven") }
        assertThrows(F.FixtureIncomplete::class.java) { F.requireInt(document, "count") }
        val other = buildJsonObject { put("flag", 1) }
        assertThrows(F.FixtureIncomplete::class.java) { F.requireBool(other, "flag") }
    }

    // ---- an empty collection must fail, not vacuously agree -------------------------------------------

    @Test
    fun `an empty collection fails with FIXTURE_EMPTY rather than satisfying every assertion`() {
        val error = assertThrows(F.FixtureIncomplete::class.java) {
            F.nonEmpty("the projections", emptyList<String>())
        }
        assertEquals(F.FIXTURE_EMPTY, error.code)
    }

    @Test
    fun `the vacuity that v10 hit is reproduced and refused`() {
        // v10's shape: with the artefacts absent, the orphan list is empty and the assertion holds.
        val orphansWhenNothingExists = emptyList<String>()
        assertEquals("this is the assertion v10 made, and it passes",
                     emptyList<String>(), orphansWhenNothingExists)
        // v11's shape: the same situation is a failed case.
        val error = assertThrows(F.FixtureIncomplete::class.java) {
            F.nonEmpty("projections to check for orphans", orphansWhenNothingExists)
        }
        assertEquals(F.FIXTURE_EMPTY, error.code)
    }

    @Test
    fun `a non-empty collection passes through unchanged`() {
        val values = listOf("K3", "K4")
        assertEquals(values, F.nonEmpty("run keys", values).toList())
    }

    // ---- negative and positive controls ------------------------------------------------------------------

    @Test
    fun `the negative control fails with the code it declares`() {
        val error = assertThrows(F.FixtureIncomplete::class.java) {
            val document = buildJsonObject { put("runs", buildJsonArray { }) }
            F.nonEmpty("runs", F.requireList(document, "runs"))
        }
        assertEquals("an empty declared list must be refused, not accepted",
                     F.FIXTURE_EMPTY, error.code)
    }

    @Test
    fun `the positive control passes on a document that is actually there`() {
        // The v11 baseline is written before any characterization runs, so it is a real subject.
        val baseline = F.requireObject(
            "integration_evidence/evaluation/ryeong_official_v11/baseline/BASELINE_v11_start.json")
        assertNotNull(baseline)
        assertTrue("the baseline must declare protected files",
                   F.requireInt(baseline, "protected_inventory/declared") > 0)
        val runs = F.nonEmpty("host runs", F.requireAt(baseline, "host_runs").let {
            (it as kotlinx.serialization.json.JsonArray).toList()
        })
        assertTrue("the baseline must record host runs", runs.isNotEmpty())
    }

    // ---- the frozen subjects the witness cases rest on ------------------------------------------------------

    @Test
    fun `the frozen v10 subjects the witness cases read are present and readable`() {
        listOf(F.V10_COMPARISON, F.V10_VERDICT, F.K10_STATUS, F.K10_RESULT, F.K10_MARKER,
               F.K10_HOST_VALIDITY).forEach { path ->
            assertTrue("$path must be readable for the witness cases to have a subject",
                       F.exists(path))
        }
        // These two are absent on purpose: K7 never ran. A case about K7 must not read absence as
        // a pass, so it reads the historical authority instead.
        assertTrue("K7 has no run status, which is the historical fact", !F.exists(F.K7_STATUS))
        assertTrue("K7 has no marker, which is the historical fact", !F.exists(F.K7_MARKER))
    }

    @Test
    fun `a case that reads absence does so through maybeObject, which is the only place it is allowed`() {
        assertEquals("maybeObject returns null for an absent path",
                     null, F.maybeObject("tools/ryeong_official_v11/contracts/absent.json"))
        assertThrows("requireObject must not", F.FixtureIncomplete::class.java) {
            F.requireObject("tools/ryeong_official_v11/contracts/absent.json")
        }
    }

    // ---- collision helpers ---------------------------------------------------------------------------------

    @Test
    fun `the collision helper sees a case-only difference as one file`() {
        val collisions = F.collisions(listOf("a/B.json", "a/b.json", "a/c.json"))
        assertEquals(1, collisions.size)
        assertEquals(listOf("a/B.json", "a/b.json"), collisions.single())
    }

    @Test
    fun `the collision helper sees nothing where there is nothing`() {
        assertEquals(emptyList<List<String>>(), F.collisions(listOf("a/x.json", "a/y.json")))
    }
}
