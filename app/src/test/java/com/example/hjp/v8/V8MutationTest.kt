package com.example.hjp.v8

import com.example.hjp.v8.V8Contracts.CARDINALITY
import com.example.hjp.v8.V8Contracts.MUTATION_REPORT
import com.example.hjp.v8.V8Contracts.PATH_POLICY
import com.example.hjp.v8.V8Contracts.bool
import com.example.hjp.v8.V8Contracts.int
import com.example.hjp.v8.V8Contracts.list
import com.example.hjp.v8.V8Contracts.obj
import com.example.hjp.v8.V8Contracts.requireObj
import com.example.hjp.v8.V8Contracts.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Break the Kotlin reader on purpose and check the break shows up.
 *
 * The Python harness in `tools/ryeong_official_v8/mutate_and_check_v8.py` does this for the contracts
 * and the JVM verdict model. This does it for the half of the pair that lives in the JVM, because a
 * parity check between two readers is only worth something if each of them can be shown to fail
 * independently. Two readers that are both blind in the same place agree perfectly.
 *
 * Every mutation is applied to an in-memory copy of the policy or the document. Nothing on disk is
 * touched.
 */
class V8MutationTest {

    private fun policy(): JsonObject = requireObj(PATH_POLICY)

    private fun v6Template(): JsonObject =
        requireObj("integration_evidence/evaluation/ryeong_official_v6/phase_b_template/" +
            "device_execution_manifest_template.json")

    private fun truth() = V8Cardinality.scan(v6Template(), policy(), "v6")

    // ==============================================================================================
    // the reader must move when the document moves
    // ==============================================================================================

    @Test
    fun `removing one of a repeated target's pointers moves the occurrence count alone`() {
        val full = truth()
        assertEquals(5, full.operationalOccurrenceCount)
        assertEquals(3, full.distinctInvalidTargetCount)

        // Drop /contact_dependency entirely: DEVICE_SCORE.json and DEVICE_RECOMPUTATION.json are
        // still named from /host_analysis, so the target count must not move and the occurrence
        // count must fall by two. A reader that deduplicated by target would report no change at
        // all — which is the v7 defect, seen from the other side.
        val trimmed = buildJsonObject {
            v6Template().forEach { (key, value) ->
                if (key != "contact_dependency") put(key, value)
            }
        }
        val after = V8Cardinality.scan(trimmed, policy(), "v6")
        assertEquals("the occurrence count must fall by two", 3, after.operationalOccurrenceCount)
        assertEquals("the target count must not move", 3, after.distinctInvalidTargetCount)
        assertNotEquals(
            "the two counts moved together, so this reader cannot tell them apart",
            full.operationalOccurrenceCount - after.operationalOccurrenceCount,
            full.distinctInvalidTargetCount - after.distinctInvalidTargetCount,
        )
    }

    @Test
    fun `adding a second pointer for an existing target moves only the occurrence count`() {
        val before = truth()
        val extended = buildJsonObject {
            v6Template().forEach { (key, value) -> put(key, value) }
            put("invented_position", "<from DEVICE_SCORE.json>")
        }
        val after = V8Cardinality.scan(extended, policy(), "v6")
        assertEquals("the occurrence count must rise by one",
            before.operationalOccurrenceCount + 1, after.operationalOccurrenceCount)
        assertEquals("the target count must not move",
            before.distinctInvalidTargetCount, after.distinctInvalidTargetCount)
    }

    @Test
    fun `adding a pointer for a new target moves both counts`() {
        val before = truth()
        val extended = buildJsonObject {
            v6Template().forEach { (key, value) -> put(key, value) }
            put("invented_position", "<from SOMETHING_ELSE.json>")
        }
        val after = V8Cardinality.scan(extended, policy(), "v6")
        assertEquals(before.operationalOccurrenceCount + 1, after.operationalOccurrenceCount)
        assertEquals("a genuinely new target must move the target count too",
            before.distinctInvalidTargetCount + 1, after.distinctInvalidTargetCount)
    }

    @Test
    fun `exempting an operational prefix hides the occurrences under it`() {
        val mutated = buildJsonObject {
            policy().forEach { (key, value) ->
                if (key == "allowed_pointer_prefixes") {
                    put(key, buildJsonArray {
                        (value as JsonArray).forEach { add(it) }
                        add(kotlinx.serialization.json.JsonPrimitive("/host_analysis"))
                    })
                } else {
                    put(key, value)
                }
            }
        }
        val after = V8Cardinality.scan(v6Template(), mutated, "v6")
        assertEquals(
            "exempting /host_analysis must hide exactly the two occurrences under it",
            3, after.operationalOccurrenceCount,
        )
        assertTrue(
            "a reader that reported the same count after an exemption is not reading the policy",
            after.operationalOccurrenceCount != truth().operationalOccurrenceCount,
        )
    }

    @Test
    fun `un-exempting the forbidden list turns a deliberate mention into a violation`() {
        // /forbidden_in_phase_b names ryeong_device_eval_v5_official on purpose: saying what was
        // excluded is the position's job. Removing the exemption produces the sixth "mention" the
        // v7 migration note counted, and shows why that count was a different unit again.
        val mutated = buildJsonObject {
            policy().forEach { (key, value) ->
                if (key == "allowed_pointer_prefixes") {
                    put(key, buildJsonArray {
                        (value as JsonArray).forEach {
                            if (str(it) != "/forbidden_in_phase_b") add(it)
                        }
                    })
                } else {
                    put(key, value)
                }
            }
        }
        val after = V8Cardinality.scan(v6Template(), mutated, "v6")
        assertTrue(
            "removing the forbidden-list exemption must produce more occurrences than the policy " +
                "does, or the exemption was doing nothing",
            after.operationalOccurrenceCount > truth().operationalOccurrenceCount,
        )
    }

    @Test
    fun `the reader is not returning constants`() {
        val rows = V8Cardinality.selfCheck(policy())
        assertEquals(3, rows.size)
        assertEquals("the three synthetic documents must give three different occurrence counts",
            listOf(0, 1, 2), rows.map { it.observedOccurrences })
        assertEquals("and two distinct target counts",
            listOf(0, 1, 1), rows.map { it.observedTargets })
        assertTrue("a self-check row disagrees with its construction", rows.all { it.agrees })
    }

    @Test
    fun `the reader distinguishes a reordering from a set difference`() {
        val full = truth()
        val reordered = full.operationalOccurrencePointers.reversed()
        assertEquals("reordering must not change the set",
            full.operationalOccurrencePointers.sorted(), reordered.sorted())
        assertNotEquals("reordering must be visible as an ordering difference",
            full.operationalOccurrencePointers, reordered)
    }

    @Test
    fun `a version token is matched as a token and not as a substring`() {
        assertTrue("_v5_ is a reference", V8Cardinality.mentionsVersion("path_v5_thing", "v5"))
        assertTrue("/v5/ is a reference", V8Cardinality.mentionsVersion("a/v5/b", "v5"))
        assertTrue("-v5. is a reference", V8Cardinality.mentionsVersion("x-v5.json", "v5"))
        assertTrue("rev5 is a build fingerprint, not a reference",
            !V8Cardinality.mentionsVersion("build rev5 here", "v5"))
        assertTrue("v54 is not a v5 reference",
            !V8Cardinality.mentionsVersion("v54", "v5"))
    }

    @Test
    fun `position classification is fail-closed`() {
        val (classification, _, why) = V8Cardinality.classifyPosition("/nobody/declared/this",
            policy())
        assertEquals("an undeclared position must be operational", "OPERATIONAL", classification)
        assertTrue("the reader does not say why it defaulted to operational",
            why.contains("fail-closed"))
    }

    // ==============================================================================================
    // the python harness must have run, and must have caught everything
    // ==============================================================================================

    @Test
    fun `the python mutation harness detected every mutation it declared`() {
        val report = obj(MUTATION_REPORT)
        assertNotNull(
            "the python mutation report is not in the tree; run " +
                "tools/ryeong_official_v8/mutate_and_check_v8.py: $MUTATION_REPORT",
            report,
        )
        assertEquals("a mutation went undetected: " +
            (report!!["undetected"] as JsonArray).map { str(it.jsonObject["id"]) },
            0, int(report["undetected_count"]))
        assertEquals("ALL MUTATIONS DETECTED", str(report["verdict"]))

        val families = report["by_family"] as JsonObject
        listOf("cardinality", "historical_jvm", "comparison", "acceptance").forEach { family ->
            val counts = families[family]?.jsonObject
            assertNotNull("the harness ran no $family mutations", counts)
            assertTrue("the $family family is empty", (int(counts!!["total"]) ?: 0) > 0)
            assertEquals("a $family mutation went undetected",
                int(counts["total"]), int(counts["detected"]))
        }
    }

    @Test
    fun `the python harness covers the two mutations that recreate the v7 defect`() {
        val report = obj(MUTATION_REPORT)
        assertNotNull("the python mutation report is not in the tree", report)
        val ids = (report!!["mutations"] as JsonArray).map { str(it.jsonObject["id"]) }
        listOf(
            "CARD_01_occurrence_as_target_count",
            "CARD_02_target_as_occurrence_count",
            "CARD_03_pointer_deduplicated_by_target",
            "CARD_05_repeated_target_pointer_dropped",
            "CARD_14_constant_five",
            "CARD_15_constant_three",
            "HIST_08_case_table_sha_changed",
            "HIST_12_historical_failure_removed",
            "HIST_14_two_failures_matched_one_entry",
        ).forEach { id ->
            assertTrue("the harness does not cover $id", ids.contains(id))
        }
    }

    @Test
    fun `the contract the harness mutated is the contract in the tree`() {
        val contract = requireObj(CARDINALITY)
        assertEquals(5, int(contract["operational_occurrence_count"]))
        assertEquals(3, int(contract["distinct_invalid_target_count"]))
        val selfCheck = (contract["anti_hardcode"] as JsonObject)["self_check"] as JsonArray
        assertEquals("the contract's own anti-hardcode check must cover three shapes",
            3, selfCheck.size)
        selfCheck.map { it.jsonObject }.forEach { row ->
            assertEquals("the contract records a self-check that did not agree",
                true, bool(row["agrees"]))
        }
    }
}
