package com.example.hjp.v10

import com.example.hjp.v10.V10Contracts.DEVIATION_INDEX
import com.example.hjp.v10.V10Contracts.DRY_RUN
import com.example.hjp.v10.V10Contracts.LIFECYCLE_CONTRACT
import com.example.hjp.v10.V10Contracts.LIFECYCLE_PATHS
import com.example.hjp.v10.V10Contracts.at
import com.example.hjp.v10.V10Contracts.bool
import com.example.hjp.v10.V10Contracts.int
import com.example.hjp.v10.V10Contracts.list
import com.example.hjp.v10.V10Contracts.obj
import com.example.hjp.v10.V10Contracts.requireObj
import com.example.hjp.v10.V10Contracts.roots
import com.example.hjp.v10.V10Contracts.str
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a tool may write, and when.
 *
 * v9 lost the ability to re-read one pre-run audit and put two files where they did not belong, and
 * none of that was against any rule, because there were no rules. These are the rules, checked
 * against the contract that states them rather than against the tools that obey them — a tool
 * checked against itself agrees with itself.
 */
class V10LifecycleTest {

    private val requiredRoots = listOf(
        "frozen_source_root", "frozen_pre_run_evidence_root", "official_run_authority_root",
        "post_run_derived_report_root", "delivery_source_root",
        "external_delivery_verification_root", "emergency_hold_report_root",
    )

    @Test
    fun `the contract separates the seven roots`() {
        assertEquals(requiredRoots.sorted(), roots().keys.sorted())
        roots().forEach { (id, root) ->
            assertNotNull("$id must declare a path", str(root["path"]))
            assertNotNull("$id must declare a phase", str(root["phase"]))
            assertNotNull("$id must declare a maximum scope", str(root["maximum_scope"]))
            assertNotNull("$id must declare a casefold policy", str(root["casefold_policy"]))
            assertNotNull("$id must declare a normalization policy",
                          str(root["unicode_normalization_policy"]))
            assertNotNull("$id must declare an overwrite policy", str(root["overwrite_policy"]))
            assertNotNull("$id must declare allowed writers", root["allowed_writers"])
            assertNotNull("$id must declare an expected file inventory",
                          root["expected_file_inventory"])
        }
    }

    @Test
    fun `a frozen root allows neither move nor delete`() {
        listOf("frozen_source_root", "frozen_pre_run_evidence_root").forEach { id ->
            val allowed = list(roots()[id]?.get("allowed_operations"))
            assertTrue("$id must not allow move", !allowed.contains("move"))
            assertTrue("$id must not allow delete", !allowed.contains("delete"))
            assertEquals("$id must allow nothing after the freeze",
                         emptyList<String>(), list(roots()[id]?.get("allowed_operations_after_freeze")))
        }
    }

    @Test
    fun `pre-run evidence is write-once`() {
        val root = roots()["frozen_pre_run_evidence_root"]
        assertEquals("create_new_only", str(root?.get("overwrite_policy")))
        assertEquals(listOf("create"), list(root?.get("allowed_operations")))
        assertNotNull("it must say how the write is made atomic", str(root?.get("atomicity")))
    }

    @Test
    fun `only the runner writes an authority artefact`() {
        val root = roots()["official_run_authority_root"]
        assertEquals(listOf("official_runner"), list(root?.get("allowed_writers")))
        assertEquals("create_new_only", str(root?.get("overwrite_policy")))
        val inventory = list(root?.get("expected_file_inventory"))
        listOf("result.json", "raw_turns.jsonl", "run_status.json", "gate_verdict.json")
            .forEach { assertTrue("the authority root must declare $it", inventory.contains(it)) }
        assertNotNull("the contract must say the authority is runner-only",
                      str(requireObj(LIFECYCLE_CONTRACT)["authority_is_runner_only"]))
    }

    @Test
    fun `the post-run root is declared before the freeze and excluded from it`() {
        val root = roots()["post_run_derived_report_root"]
        assertEquals("post_run", str(root?.get("phase")))
        assertEquals(false, bool(root?.get("included_in_freeze")))
        assertEquals("create_new_only", str(root?.get("overwrite_policy")))
        assertTrue("it must declare the reports it will hold",
                   list(root?.get("expected_file_inventory")).isNotEmpty())
        assertEquals(true, bool(requireObj(LIFECYCLE_CONTRACT)["declared_before_the_freeze"]))
    }

    @Test
    fun `the emergency hold root exists so recording a late defect is not itself a violation`() {
        val root = roots()["emergency_hold_report_root"]
        assertEquals("post_freeze", str(root?.get("phase")))
        assertEquals(false, bool(root?.get("included_in_freeze")))
        assertEquals("create_new_only", str(root?.get("overwrite_policy")))
        assertTrue("it must declare what it will hold",
                   list(root?.get("expected_file_inventory")).isNotEmpty())
    }

    @Test
    fun `delivery verification lives outside the tree it verifies`() {
        val external = str(roots()["external_delivery_verification_root"]?.get("path")) ?: ""
        val source = str(roots()["delivery_source_root"]?.get("path")) ?: ""
        assertTrue("both roots must be declared", external.isNotEmpty() && source.isNotEmpty())
        assertTrue("the external root must not be inside the delivery source root",
                   !external.startsWith("$source/") && external != source)
        assertEquals(false, bool(roots()["external_delivery_verification_root"]
            ?.get("included_in_bundle")))
        assertNotNull("the contract must say why a zip cannot carry its own digest",
                      str(requireObj(LIFECYCLE_CONTRACT)["why_a_zip_cannot_contain_its_own_digest"]))
    }

    @Test
    fun `the forbidden remedies name every way v9 could have hidden a mismatch`() {
        val forbidden = list(requireObj(LIFECYCLE_CONTRACT)["forbidden_remedies"])
        listOf("editing a frozen source", "moving", "deleting", "regenerating the freeze",
               "replacing a pre-run report", "broadening an exclusion").forEach { phrase ->
            assertTrue("the contract must forbid: $phrase; it lists $forbidden",
                       forbidden.any { it.contains(phrase.split(" ").first()) })
        }
    }

    @Test
    fun `declared outputs carry their stage and collide with nothing`() {
        val declared = roots().flatMap { (id, root) ->
            list(root["expected_file_inventory"]).map { "${str(root["path"])}/$it" }
        }
        assertTrue("there must be declared outputs", declared.isNotEmpty())
        assertEquals("no path may be declared twice", declared.size, declared.distinct().size)

        val folded = declared.groupBy { Normalizer.normalize(it, Normalizer.Form.NFC).lowercase() }
        assertEquals("casefold collisions among declared outputs",
                     emptyList<List<String>>(),
                     folded.values.filter { it.distinct().size > 1 }.map { it.distinct().sorted() })

        val normalized = declared.groupBy { Normalizer.normalize(it, Normalizer.Form.NFD) }
        assertEquals("normalization collisions among declared outputs",
                     emptyList<List<String>>(),
                     normalized.values.filter { it.distinct().size > 1 }
                         .map { it.distinct().sorted() })

        val staged = declared.filter { it.contains("_post_k10") || it.contains("_post_delivery") }
        assertTrue("stage-bearing names must be in use", staged.isNotEmpty())
    }

    @Test
    fun `the path validation found nothing`() {
        val validation = obj(LIFECYCLE_PATHS)
        assertNotNull("the pre-K10 lifecycle path validation must be in the tree", validation)
        listOf("casefold_collisions", "unicode_collisions", "post_run_into_frozen",
               "over_broad_roots", "source_under_excluded_root", "duplicate_declared_paths")
            .forEach { key ->
                assertEquals("$key must be zero", 0, int(at(validation, "totals/$key")))
            }
        assertEquals("LIFECYCLE PATHS CLEAN", str(validation?.get("verdict")))
    }

    @Test
    fun `an exclusion may not reach source`() {
        val contract = requireObj(LIFECYCLE_CONTRACT)
        assertNotNull("exclusions must be declared", contract["excluded_roots"])
        assertNotNull("the exclusion rule must be stated", str(contract["exclusion_rule"]))
        assertEquals(0, int(at(obj(LIFECYCLE_PATHS), "totals/source_under_excluded_root")))
    }

    @Test
    fun `the dry-run proved the rules before the freeze rather than after it`() {
        val dryRun = obj(DRY_RUN)
        assertNotNull("the post-processing dry-run must be in the tree", dryRun)
        listOf("writes_outside_declared_roots", "frozen_source_writes",
               "pre_run_overwrites", "authority_modifications", "unexpected_creates",
               "casefold_collisions", "unicode_collisions").forEach { key ->
            assertEquals("$key must be zero", 0, int(at(dryRun, "totals/$key")))
        }
        assertEquals(true, bool(at(dryRun, "checks/rerun_refuses_overwrite")))
        assertEquals(true, bool(at(dryRun, "checks/only_root_prefix_differs")))
        assertEquals(true, bool(at(dryRun, "checks/emergency_report_only_on_failure_injection")))
        assertEquals(emptyList<String>(), list(dryRun?.get("path_inventory_difference")))
    }

    @Test
    fun `the three v9 deviations are witnessed, one record each`() {
        val index = obj(DEVIATION_INDEX)
        assertNotNull("the deviation index must be in the tree", index)
        val codes = list(index?.get("records")).ifEmpty {
            // records is a list of objects; pull the codes out of it
            (index?.get("records") as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { str((it as? kotlinx.serialization.json.JsonObject)?.get("code")) }
                .orEmpty()
        }
        listOf("FROZEN_SOURCE_CHANGED_AFTER_RUN", "POST_RUN_FILE_ADDED_TO_FROZEN_ROOT",
               "PRE_RUN_EVIDENCE_OVERWRITTEN").forEach { code ->
            assertTrue("the index must carry $code; it has $codes", codes.contains(code))
        }
        assertEquals("no v9 deviation touched an authority artefact",
                     false, bool(index?.get("all_three_touch_an_authority_artifact")))
    }
}
