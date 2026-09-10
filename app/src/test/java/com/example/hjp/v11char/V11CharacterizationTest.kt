package com.example.hjp.v11char

import com.example.hjp.v11char.V11CharacterizationFixture as F
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v11 characterization: fifty-four cases, written before the v11 implementation existed.
 *
 * Six read the frozen v10 tree and the two witnesses built from it, and are true at RED. The other
 * forty-eight reach for a v11 artefact through an accessor that raises FIXTURE_INCOMPLETE when the
 * artefact is absent — so at RED they fail naming what they wanted, rather than passing because
 * there was nothing to disagree with. That property is checked by V11HarnessSelfTest before these
 * inputs are frozen, because it is the property v10's characterization did not have.
 */
class V11CharacterizationTest {

    // =============================================================================================
    // C01 - C06  what v10 actually did
    // =============================================================================================

    @Test
    fun `C01 K10's authority says it completed`() {
        val status = F.requireObject(F.K10_STATUS)
        assertTrue("the marker must exist", F.exists(F.K10_MARKER))
        assertTrue("the result must exist", F.exists(F.K10_RESULT))
        assertTrue("the raw record must exist", F.exists(F.K10_RAW))
        assertEquals(1, F.requireInt(status, "invocations"))
        assertEquals(true, F.requireBool(status, "completed"))
        assertEquals(false, F.requireBool(status, "partial"))
        assertEquals("VALID BASELINE", F.requireString(status, "validity"))
    }

    @Test
    fun `C02 v10 carried that completed run as not yet executed`() {
        val comparison = F.requireObject(F.V10_COMPARISON)
        val carried = F.requireKeys(comparison, "runs_carried_as_state")
        assertTrue("K10 must be among the runs carried as a state", carried.contains("K10"))
        assertEquals("NOT_YET_EXECUTED",
                     F.requireString(comparison, "runs_carried_as_state/K10/run_state"))
        assertTrue("K10 must not be among the runs present",
                   !F.requireList(comparison, "runs_present").contains("K10"))
    }

    @Test
    fun `C03 and it carries none of K10's metrics`() {
        val comparison = F.requireObject(F.V10_COMPARISON)
        val reads = F.requireAt(comparison, "run_metric_reads") as JsonObject
        val withMetrics = F.nonEmpty("runs with metric rows",
                                     reads.filterValues { (it as? JsonObject)?.isNotEmpty() == true }
                                         .keys)
        val peer = withMetrics.first()
        val peerCount = (reads[peer] as JsonObject).size
        val k10Count = (reads["K10"] as? JsonObject)?.size ?: 0
        assertTrue("a completed peer must publish metrics", peerCount > 0)
        assertEquals("K10 published none of them", 0, k10Count)
    }

    @Test
    fun `C04 K7's hold is a historical fact, not an inference from absence`() {
        val witness = F.requireObject(F.RUN_STATE_WITNESS)
        assertEquals(listOf("NOT_OFFICIAL_V10_REJUDGMENT", "READ_ONLY_V11_WITNESS"),
                     F.requireList(witness, "status"))
        assertTrue("K7 has no run status in the tree", !F.exists(F.K7_STATUS))
        assertTrue("K7 has no marker in the tree", !F.exists(F.K7_MARKER))
        val v10Comparison = F.requireObject(F.V10_COMPARISON)
        assertEquals("NOT_RUN_DUE_TO_PREFLIGHT_HOLD",
                     F.requireString(v10Comparison, "runs_carried_as_state/K7/run_state"))
    }

    @Test
    fun `C05 v10's declared freeze output names differ from the ones produced`() {
        val witness = F.requireObject(F.STAGE_OUTPUT_WITNESS)
        assertEquals(false, F.requireBool(witness, "name_declaration_finding/declared_matches_actual"))
        val undeclared = F.nonEmpty("names produced but not declared",
            F.requireList(witness, "name_declaration_finding/undeclared_names_produced"))
        assertTrue("the produced names must include the freeze report",
                   undeclared.any { it.contains("freeze_verification") })
        assertEquals("the verification itself passed", true,
                     F.requireBool(witness, "name_declaration_finding/the_verification_passed"))
    }

    @Test
    fun `C06 v10's post-delivery freeze verification never happened`() {
        val witness = F.requireObject(F.STAGE_OUTPUT_WITNESS)
        assertEquals(false, F.requireBool(witness, "post_delivery_finding/post_delivery_report_present"))
        assertEquals(true, F.requireBool(witness, "post_delivery_finding/target_already_exists"))
        assertEquals(true, F.requireBool(witness,
            "post_delivery_finding/but_a_freeze_verification_was_still_required_and_did_not_happen"))
    }

    // =============================================================================================
    // C07 - C26  execution state, derived at read time
    // =============================================================================================

    private fun observed(path: String): JsonObject = F.requireObject(path)

    @Test
    fun `C07 a run declared_future at contract time reads COMPLETED once its authority exists`() {
        val states = observed(F.OBSERVED_PRE)
        assertEquals("COMPLETED", F.observedState(states, "K10"))
        val registry = F.requireObject(F.REGISTRY)
        assertTrue("the registry must not carry an execution state for K10",
                   F.at(registry, "runs/K10/execution_state") == null)
    }

    @Test
    fun `C08 a completed run carries metrics in the comparison`() {
        val states = observed(F.OBSERVED_PRE)
        val comparison = F.requireObject(F.COMPARISON_PRE)
        val runs = F.requireAt(states, "runs") as JsonObject
        val completed = F.nonEmpty("completed runs",
            runs.filter { F.str(F.at(it.value as JsonObject, "execution_state")) == "COMPLETED" }
                .keys)
        val reads = F.requireAt(comparison, "run_metric_reads") as JsonObject
        val withoutMetrics = completed.filter { (reads[it] as? JsonObject).isNullOrEmpty() }
        assertEquals("every completed run must have metrics", emptyList<String>(),
                     withoutMetrics.sorted())
    }

    @Test
    fun `C09 a completed run missing its metrics raises COMPLETED_RUN_METRICS_MISSING`() {
        val validation = F.requireObject(F.COMPARISON_PRE_VALIDATION)
        val codes = F.nonEmpty("failure codes the validator can raise",
                               F.requireList(validation, "detectable_failure_codes"))
        assertTrue("the code must exist; the validator declares $codes",
                   codes.contains("COMPLETED_RUN_METRICS_MISSING"))
        assertEquals(0, F.requireInt(validation, "totals/completed_runs_missing_metrics"))
    }

    @Test
    fun `C10 a current run with no marker, status, result or raw reads NOT_YET_EXECUTED`() {
        val states = observed(F.OBSERVED_PRE)
        assertEquals("NOT_YET_EXECUTED", F.observedState(states, "K11"))
        assertEquals(0, F.requireInt(states, "runs/K11/evidence/present_count"))
    }

    @Test
    fun `C11 a marker alone does not make a run completed`() {
        val selftest = F.requireObject(F.STATE_SELFTEST)
        assertEquals("MARKER_ONLY",
                     F.requireString(selftest, "scenarios/marker_only/expected_state_family"))
        assertTrue("a marker-only tree must not read COMPLETED",
                   F.requireString(selftest, "scenarios/marker_only/observed_state") != "COMPLETED")
    }

    @Test
    fun `C12 a completed status without a result or raw is INCONSISTENT`() {
        val selftest = F.requireObject(F.STATE_SELFTEST)
        assertEquals("INCONSISTENT",
                     F.requireString(selftest, "scenarios/status_without_records/observed_state"))
    }

    @Test
    fun `C13 a result or raw with no marker is an orphan authority`() {
        val selftest = F.requireObject(F.STATE_SELFTEST)
        assertEquals("INCONSISTENT",
                     F.requireString(selftest, "scenarios/records_without_marker/observed_state"))
        assertTrue("the reason must name the orphan",
                   F.requireList(selftest, "scenarios/records_without_marker/reasons")
                       .any { it.contains("ORPHAN") })
    }

    @Test
    fun `C14 a partial file prevents COMPLETED`() {
        val selftest = F.requireObject(F.STATE_SELFTEST)
        assertEquals("PARTIAL",
                     F.requireString(selftest, "scenarios/partial_present/observed_state"))
    }

    @Test
    fun `C15 invocations of zero or more than one is not an official completed run`() {
        val selftest = F.requireObject(F.STATE_SELFTEST)
        listOf("invocations_zero", "invocations_two").forEach { scenario ->
            assertTrue("$scenario must not read COMPLETED",
                       F.requireString(selftest, "scenarios/$scenario/observed_state") != "COMPLETED")
        }
    }

    @Test
    fun `C16 status, result and raw digests that disagree make the run INCONSISTENT`() {
        val selftest = F.requireObject(F.STATE_SELFTEST)
        assertEquals("INCONSISTENT",
                     F.requireString(selftest, "scenarios/digest_mismatch/observed_state"))
    }

    @Test
    fun `C17 an unregistered schema or malformed JSON reads UNREADABLE`() {
        val selftest = F.requireObject(F.STATE_SELFTEST)
        listOf("unknown_schema", "malformed_json").forEach { scenario ->
            assertEquals("$scenario must read UNREADABLE", "UNREADABLE",
                         F.requireString(selftest, "scenarios/$scenario/observed_state"))
        }
    }

    @Test
    fun `C18 a duplicate JSON key is refused`() {
        val selftest = F.requireObject(F.STATE_SELFTEST)
        assertEquals("UNREADABLE",
                     F.requireString(selftest, "scenarios/duplicate_key/observed_state"))
        assertTrue("the reason must name the duplicate key",
                   F.requireList(selftest, "scenarios/duplicate_key/reasons")
                       .any { it.contains("DUPLICATE") })
    }

    @Test
    fun `C19 a run that did not execute may not carry metrics`() {
        val comparison = F.requireObject(F.COMPARISON_PRE)
        val states = observed(F.OBSERVED_PRE)
        val runs = F.requireAt(states, "runs") as JsonObject
        val notExecuted = F.nonEmpty("runs that did not execute",
            runs.filter {
                F.str(F.at(it.value as JsonObject, "execution_state")) in
                    listOf("NOT_YET_EXECUTED", "NOT_RUN_DUE_TO_PREFLIGHT_HOLD")
            }.keys)
        val reads = F.requireAt(comparison, "run_metric_reads") as JsonObject
        val withMetrics = notExecuted.filter { (reads[it] as? JsonObject)?.isNotEmpty() == true }
        assertEquals("a run that did not execute has no metrics to carry",
                     emptyList<String>(), withMetrics.sorted())
    }

    @Test
    fun `C20 K7's hold comes from frozen historical authority, not from current file absence`() {
        val contract = F.requireObject(F.STATE_CONTRACT)
        val authority = F.requireString(contract, "historical_holds/K7/authority_path")
        assertTrue("the declared authority must be in the tree", F.exists(authority))
        val states = observed(F.OBSERVED_PRE)
        assertEquals("NOT_RUN_DUE_TO_PREFLIGHT_HOLD", F.observedState(states, "K7"))
        assertEquals(authority, F.requireString(states, "runs/K7/state_authority_path"))
    }

    @Test
    fun `C21 the real K10 reads COMPLETED and its fifteen metrics appear`() {
        val states = observed(F.OBSERVED_PRE)
        assertEquals("COMPLETED", F.observedState(states, "K10"))
        val comparison = F.requireObject(F.COMPARISON_PRE)
        val metrics = F.requireAt(comparison, "run_metric_reads/K10") as JsonObject
        val required = F.requireInt(comparison, "required_metric_count")
        assertEquals("K10 must publish every required metric", required, metrics.size)
    }

    @Test
    fun `C22 a synthetic K11 reads NOT_YET_EXECUTED before its authority exists and COMPLETED after`() {
        val selftest = F.requireObject(F.STATE_SELFTEST)
        assertEquals("NOT_YET_EXECUTED",
                     F.requireString(selftest, "scenarios/synthetic_absent/observed_state"))
        assertEquals("COMPLETED",
                     F.requireString(selftest, "scenarios/synthetic_complete/observed_state"))
        assertEquals("the same resolver produced both", true,
                     F.requireBool(selftest, "same_resolver_digest_for_every_scenario"))
    }

    @Test
    fun `C23 the same frozen driver and contract give different observations pre-run and post-run`() {
        val selftest = F.requireObject(F.STATE_SELFTEST)
        assertEquals(true, F.requireBool(selftest, "same_resolver_digest_for_every_scenario"))
        assertEquals(true, F.requireBool(selftest, "no_file_edited_between_scenarios"))
        val distinct = F.nonEmpty("distinct observed states across scenarios",
                                  F.requireList(selftest, "distinct_states_observed"))
        assertTrue("the resolver must be able to return more than one answer", distinct.size > 1)
    }

    @Test
    fun `C24 registry identity and observed execution state are separate fields`() {
        val registry = F.requireObject(F.REGISTRY)
        val runs = F.requireAt(registry, "runs") as JsonObject
        F.nonEmpty("registry runs", runs.keys)
        val forbidden = listOf("execution_state", "executed", "present", "completed", "partial",
                               "current_validity", "invocations", "metric_presence")
        val offenders = runs.flatMap { (key, value) ->
            forbidden.filter { (value as JsonObject).containsKey(it) }.map { "$key.$it" }
        }
        assertEquals("the registry may not hold an observed state", emptyList<String>(),
                     offenders.sorted())
    }

    @Test
    fun `C25 storing a current state as a literal in the registry or contract is refused`() {
        val registry = F.requireObject(F.REGISTRY)
        assertEquals(true, F.requireBool(registry, "identity_and_topology_only"))
        val forbidden = F.nonEmpty("fields the registry refuses to hold",
                                   F.requireList(registry, "forbidden_observed_state_fields"))
        assertTrue("the list must name execution state",
                   forbidden.contains("execution_state"))
        val mutation = F.requireObject(F.MUTATION)
        val ids = F.nonEmpty("mutation ids",
            (F.requireAt(mutation, "mutations") as JsonArray)
                .mapNotNull { F.str(F.at(it as JsonObject, "id")) })
        assertTrue("a mutation must put a literal state in the registry and be caught",
                   ids.any { it.contains("static_state") })
    }

    @Test
    fun `C26 a mutation that carries a completed run as NOT_YET_EXECUTED is detected`() {
        val mutation = F.requireObject(F.MUTATION)
        val entries = (F.requireAt(mutation, "mutations") as JsonArray).map { it as JsonObject }
        val target = entries.firstOrNull {
            (F.str(F.at(it, "id")) ?: "").contains("completed_run_as_not_yet_executed")
        }
        assertNotNull("the v10 defect must be a mutation", target)
        assertEquals(true, F.bool(F.at(target!!, "detected")))
        assertEquals("COMPLETED_RUN_STATE_MISREPORTED",
                     F.str(F.at(target, "expected_failure_code")))
    }

    // =============================================================================================
    // C27 - C44  where a tool may write, and when
    // =============================================================================================

    private fun stagePaths(): Map<String, String> {
        val contract = F.requireObject(F.LIFECYCLE_CONTRACT)
        val stages = F.requireAt(contract, "freeze_stages") as JsonObject
        F.nonEmpty("declared freeze stages", stages.keys)
        return stages.mapValues { F.requireString(it.value as JsonObject, "freeze_report_path") }
    }

    @Test
    fun `C27 the three stages use three different output paths`() {
        val paths = stagePaths()
        assertEquals("every declared stage must be present",
                     F.STAGES.sorted(), paths.keys.sorted())
        assertEquals("three stages, three paths", paths.size, paths.values.toSet().size)
    }

    @Test
    fun `C28 freeze report and checked-path report are both stage-separated`() {
        val contract = F.requireObject(F.LIFECYCLE_CONTRACT)
        val stages = F.requireAt(contract, "freeze_stages") as JsonObject
        val reports = stages.map { F.requireString(it.value as JsonObject, "freeze_report_path") }
        val checked = stages.map { F.requireString(it.value as JsonObject, "checked_paths_path") }
        assertEquals("freeze reports must be distinct", reports.size, reports.toSet().size)
        assertEquals("checked-path reports must be distinct", checked.size, checked.toSet().size)
        assertEquals("and the two families must not collide",
                     emptyList<List<String>>(), F.collisions(reports + checked))
    }

    @Test
    fun `C29 the contract's exact output path equals the path actually produced`() {
        val validation = F.requireObject(F.LIFECYCLE_VALIDATION)
        assertEquals("declared and produced paths must match exactly",
                     emptyList<String>(), F.requireList(validation, "declared_vs_produced_mismatch"))
        assertEquals(0, F.requireInt(validation, "totals/path_declaration_mismatch"))
    }

    @Test
    fun `C30 an existing stage output is never overwritten and the tool exits non-zero`() {
        val dryRun = F.requireObject(F.DRY_RUN)
        assertEquals(true, F.requireBool(dryRun, "checks/rerun_refuses_overwrite"))
        assertTrue("the refusal must be a non-zero exit",
                   F.requireInt(dryRun, "rerun/exit_code") != 0)
    }

    @Test
    fun `C31 re-running the same stage is an explicit refusal`() {
        val dryRun = F.requireObject(F.DRY_RUN)
        assertEquals(true, F.requireBool(dryRun, "checks/refusal_recorded_outside_the_target"))
        assertEquals(0, F.requireInt(dryRun, "totals/overwrites"))
    }

    @Test
    fun `C32 running a different stage leaves the other stages' files untouched`() {
        val dryRun = F.requireObject(F.DRY_RUN)
        assertEquals(true, F.requireBool(dryRun, "checks/other_stage_outputs_unchanged"))
    }

    @Test
    fun `C33 no post-run file is written into the frozen source root`() {
        val dryRun = F.requireObject(F.DRY_RUN)
        assertEquals(0, F.requireInt(dryRun, "totals/frozen_source_writes"))
        val lifecycle = F.requireObject(F.LIFECYCLE_VALIDATION)
        assertEquals(0, F.requireInt(lifecycle, "totals/post_run_into_frozen"))
    }

    @Test
    fun `C34 pre-run evidence is never overwritten by a post-run artefact`() {
        val dryRun = F.requireObject(F.DRY_RUN)
        assertEquals(0, F.requireInt(dryRun, "totals/pre_run_overwrites"))
    }

    @Test
    fun `C35 only the runner writes the authority root`() {
        val contract = F.requireObject(F.LIFECYCLE_CONTRACT)
        assertEquals(listOf("official_runner"),
                     F.requireList(contract, "roots/official_run_authority/allowed_writers"))
    }

    @Test
    fun `C36 no postprocessor modifies raw, result, status or marker`() {
        val dryRun = F.requireObject(F.DRY_RUN)
        assertEquals(0, F.requireInt(dryRun, "totals/authority_modifications"))
        assertEquals(true, F.requireBool(dryRun, "checks/authority_digests_unchanged"))
    }

    @Test
    fun `C37 casefold and Unicode normalization collisions are refused before the write`() {
        val lifecycle = F.requireObject(F.LIFECYCLE_VALIDATION)
        assertEquals(0, F.requireInt(lifecycle, "totals/casefold_collisions"))
        assertEquals(0, F.requireInt(lifecycle, "totals/unicode_collisions"))
        val declared = F.nonEmpty("declared output paths",
                                  F.requireList(lifecycle, "declared_output_paths"))
        assertEquals(emptyList<List<String>>(), F.collisions(declared.toList()))
    }

    @Test
    fun `C38 absolute, traversing, symlinked and malformed relative paths are refused`() {
        val lifecycle = F.requireObject(F.LIFECYCLE_VALIDATION)
        listOf("absolute_paths", "traversing_paths", "symlink_paths", "malformed_paths")
            .forEach { key -> assertEquals("$key must be zero", 0,
                                           F.requireInt(lifecycle, "totals/$key")) }
    }

    @Test
    fun `C39 producing an undeclared output file fails`() {
        val dryRun = F.requireObject(F.DRY_RUN)
        assertEquals(emptyList<String>(), F.requireList(dryRun, "undeclared_outputs"))
        assertEquals(true, F.requireBool(dryRun, "checks/every_produced_output_is_declared"))
    }

    @Test
    fun `C40 failing to produce a declared required output fails`() {
        val dryRun = F.requireObject(F.DRY_RUN)
        assertEquals(emptyList<String>(), F.requireList(dryRun, "declared_but_not_produced"))
    }

    @Test
    fun `C41 skipping the post-delivery freeze verification prevents READY`() {
        val gates = F.requireObject(F.GATE_CONTRACT)
        val required = F.nonEmpty("required hard gates",
                                  F.requireList(gates, "required_hard_gates"))
        assertTrue("the post-delivery freeze must be a required hard gate; the contract lists " +
                   "$required",
                   required.any { it.contains("post_delivery") && it.contains("freeze") })
    }

    @Test
    fun `C42 an emergency hold report appears only when a failure is injected`() {
        val dryRun = F.requireObject(F.DRY_RUN)
        assertEquals(true,
                     F.requireBool(dryRun, "checks/emergency_report_only_on_failure_injection"))
    }

    @Test
    fun `C43 every real postprocessor was executed in the dry-run`() {
        val dryRun = F.requireObject(F.DRY_RUN)
        val declared = F.nonEmpty("declared postprocessors",
                                  F.requireList(dryRun, "declared_postprocessors"))
        val executed = F.nonEmpty("executed postprocessors",
                                  F.requireList(dryRun, "executed_postprocessors"))
        assertEquals("every declared postprocessor must have run",
                     declared.toList().sorted(), executed.toList().sorted())
    }

    @Test
    fun `C44 a tool that is only named is not counted as executed`() {
        val dryRun = F.requireObject(F.DRY_RUN)
        assertEquals(emptyList<String>(), F.requireList(dryRun, "named_but_not_executed"))
        assertEquals(true, F.requireBool(dryRun, "checks/naming_is_not_execution"))
    }

    // =============================================================================================
    // C45 - C54  a verdict that cannot miss a blocker
    // =============================================================================================

    @Test
    fun `C45 every blocking code appears in at least one hard gate result`() {
        val gates = F.requireObject(F.GATE_CONTRACT)
        val mapping = F.requireAt(gates, "blocking_code_to_gate") as JsonObject
        F.nonEmpty("blocking codes with a gate", mapping.keys)
        val unmapped = mapping.filterValues { F.list(it).isEmpty() }.keys
        assertEquals("every blocking code must name a gate", emptySet<String>(), unmapped)
    }

    @Test
    fun `C46 a blocker absent from every gate report fails verdict generation`() {
        val gates = F.requireObject(F.GATE_CONTRACT)
        assertTrue("the contract must declare the hidden-blocker failure",
                   F.requireList(gates, "verdict_failure_codes")
                       .contains("HIDDEN_BLOCKER_NOT_COVERED_BY_ANY_GATE"))
    }

    @Test
    fun `C47 any emergency hold report prevents READY`() {
        val gates = F.requireObject(F.GATE_CONTRACT)
        assertEquals(true, F.requireBool(gates, "emergency_hold_blocks_ready"))
    }

    @Test
    fun `C48 any failed hard gate prevents READY`() {
        val gates = F.requireObject(F.GATE_CONTRACT)
        assertEquals(true, F.requireBool(gates, "any_failed_hard_gate_blocks_ready"))
        assertEquals(false, F.requireBool(gates, "partial_readiness_permitted"))
    }

    @Test
    fun `C49 a blocking code with zero failed gates raises BLOCKER_GATE_COVERAGE_MISMATCH`() {
        val gates = F.requireObject(F.GATE_CONTRACT)
        assertTrue(F.requireList(gates, "verdict_failure_codes")
                       .contains("BLOCKER_GATE_COVERAGE_MISMATCH"))
        assertNotNull("the contract must say why this exists",
                      F.requireString(gates, "why_blocker_gate_coverage_mismatch_exists"))
    }

    @Test
    fun `C50 comparison validity requires all four axes`() {
        val validation = F.requireObject(F.COMPARISON_PRE_VALIDATION)
        val axes = F.nonEmpty("comparison axes", F.requireKeys(validation, "axes"))
        assertEquals(setOf("field_validity", "run_set_completeness", "observed_state_consistency",
                           "completed_run_metric_completeness"), axes.toSet())
        assertEquals(true, F.requireBool(validation, "all_axes_required_for_valid"))
    }

    @Test
    fun `C51 completed-run metric completeness is one of those axes`() {
        val validation = F.requireObject(F.COMPARISON_PRE_VALIDATION)
        assertEquals("PASS",
                     F.requireString(validation, "axes/completed_run_metric_completeness/verdict"))
        assertEquals(0, F.requireInt(validation, "totals/completed_runs_missing_metrics"))
    }

    @Test
    fun `C52 bundle integrity alone does not make a version READY`() {
        val gates = F.requireObject(F.GATE_CONTRACT)
        assertEquals(false, F.requireBool(gates, "bundle_integrity_alone_grants_ready"))
    }

    @Test
    fun `C53 a missing post-delivery verification prevents READY`() {
        val gates = F.requireObject(F.GATE_CONTRACT)
        val required = F.requireList(gates, "required_hard_gates")
        assertTrue("post-delivery verification must be required",
                   required.any { it.contains("post_delivery") })
        assertEquals(true, F.requireBool(gates, "missing_required_gate_blocks_ready"))
    }

    @Test
    fun `C54 the gate denominator and the required gate list come from the contract`() {
        val gates = F.requireObject(F.GATE_CONTRACT)
        val required = F.nonEmpty("required hard gates",
                                  F.requireList(gates, "required_hard_gates"))
        assertEquals("the denominator must be the length of the declared list",
                     required.size, F.requireInt(gates, "required_hard_gate_count"))
        assertEquals(true, F.requireBool(gates, "denominator_derived_from_this_contract"))
    }
}
