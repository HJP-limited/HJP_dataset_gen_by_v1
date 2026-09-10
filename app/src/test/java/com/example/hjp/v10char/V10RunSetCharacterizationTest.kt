package com.example.hjp.v10char

import com.example.hjp.v10char.V10CharacterizationFixture.CONSUMER_CONTRACT
import com.example.hjp.v10char.V10CharacterizationFixture.CONSUMER_INVENTORY
import com.example.hjp.v10char.V10CharacterizationFixture.CONSUMER_RUN_SETS
import com.example.hjp.v10char.V10CharacterizationFixture.D1
import com.example.hjp.v10char.V10CharacterizationFixture.D2
import com.example.hjp.v10char.V10CharacterizationFixture.D3
import com.example.hjp.v10char.V10CharacterizationFixture.DEVIATION_INDEX
import com.example.hjp.v10char.V10CharacterizationFixture.DRY_RUN
import com.example.hjp.v10char.V10CharacterizationFixture.ENUMERATION_SCAN
import com.example.hjp.v10char.V10CharacterizationFixture.LIFECYCLE_CONTRACT
import com.example.hjp.v10char.V10CharacterizationFixture.LIFECYCLE_PATHS
import com.example.hjp.v10char.V10CharacterizationFixture.MEMBERSHIP_FIELDS
import com.example.hjp.v10char.V10CharacterizationFixture.REGISTRY
import com.example.hjp.v10char.V10CharacterizationFixture.REQUIRED_ROOTS
import com.example.hjp.v10char.V10CharacterizationFixture.REQUIRED_RUNS
import com.example.hjp.v10char.V10CharacterizationFixture.RUN_SET_WITNESS
import com.example.hjp.v10char.V10CharacterizationFixture.V9_COMPARISON
import com.example.hjp.v10char.V10CharacterizationFixture.V9_COMPARISON_VALIDATION
import com.example.hjp.v10char.V10CharacterizationFixture.at
import com.example.hjp.v10char.V10CharacterizationFixture.bool
import com.example.hjp.v10char.V10CharacterizationFixture.collisions
import com.example.hjp.v10char.V10CharacterizationFixture.consumers
import com.example.hjp.v10char.V10CharacterizationFixture.declaredOutputs
import com.example.hjp.v10char.V10CharacterizationFixture.int
import com.example.hjp.v10char.V10CharacterizationFixture.list
import com.example.hjp.v10char.V10CharacterizationFixture.obj
import com.example.hjp.v10char.V10CharacterizationFixture.projectionRuns
import com.example.hjp.v10char.V10CharacterizationFixture.projections
import com.example.hjp.v10char.V10CharacterizationFixture.registryRuns
import com.example.hjp.v10char.V10CharacterizationFixture.roots
import com.example.hjp.v10char.V10CharacterizationFixture.str
import com.example.hjp.v10char.V10CharacterizationFixture.witnessSites
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v10 characterization: 36 cases, written before the v10 implementation existed.
 *
 * Twenty of them ask who decides which runs exist and whether anything checks the answer. Sixteen
 * ask where a tool may write and when. Nine read only the frozen v9 tree and the two witnesses
 * built from it, so they are true at RED; the other twenty-seven need a v10 registry, projections,
 * a consumer inventory, a lifecycle contract or a dry-run, none of which exists at RED.
 *
 * A method name begins with its case id, or with a range when one method stands for several.
 */
class V10RunSetCharacterizationTest {

    // =============================================================================================
    // Run-set closure — what v9 actually was
    // =============================================================================================

    @Test
    fun `C01 the v9 registry carries identity and no membership metadata`() {
        val witness = obj(RUN_SET_WITNESS)
        assertNotNull("the v9 run-set witness must be in the tree", witness)
        val missing = list(at(witness, "v9_registry/what_the_registry_does_not_carry"))
        assertTrue(
            "the witness must record what the v9 registry does not carry; it recorded $missing",
            missing.any { it.contains("ordinal") } &&
                missing.any { it.contains("run_kind") } &&
                missing.any { it.contains("lifecycle_state") } &&
                missing.any { it.contains("include_in_") } &&
                missing.any { it.contains("projection") },
        )
        val runs = (at(witness, "v9_registry/per_run_state") as? JsonObject)?.keys.orEmpty()
        assertTrue("the v9 registry declared 9 runs; witness saw ${runs.size}", runs.size == 9)
    }

    @Test
    fun `C02 at least one v9 operational consumer held a run list as a source literal`() {
        val sites = witnessSites()
        assertTrue("the witness must enumerate the manual sites; it found none", sites.isNotEmpty())
        val literalSites = sites.filter { bool(it["derived_from_registry"]) == false }
        assertTrue(
            "every enumerated site must be recorded as not derived from the registry",
            literalSites.size == sites.size,
        )
        assertTrue(
            "the witness must find more sites than the five the v9 report named; it found " +
                "${sites.size}",
            sites.size > 5,
        )
    }

    @Test
    fun `C03 a file named compare_k3_to_k9 was frozen with a list that stopped at K8`() {
        val witness = obj(RUN_SET_WITNESS)
        val atFreeze = list(at(witness, "k9_near_miss/list_at_freeze_time"))
        assertEquals(
            "the freeze-time list must be K3 through K8",
            listOf("K3", "K4", "K5", "K6", "K7", "K8"), atFreeze,
        )
        assertTrue(
            "the driver must be recorded as changed after the v9 freeze",
            bool(at(witness, "k9_near_miss/changed_after_the_freeze")) == true,
        )
    }

    @Test
    fun `C04 whether the v9 output contained K9 is read from the output file`() {
        val comparison = obj(V9_COMPARISON)
        assertNotNull("the v9 comparison output must be readable", comparison)
        val present = list(comparison?.get("runs_present"))
        val witness = obj(RUN_SET_WITNESS)
        val recorded = bool(at(witness, "output_run_set_checks/v9_k3_to_k9_comparison/k9_included"))
        assertEquals(
            "the witness's k9_included must equal what the output file actually says",
            present.contains("K9"), recorded,
        )
    }

    @Test
    fun `C05 the v9 output was complete while the frozen source that produced it was not`() {
        val witness = obj(RUN_SET_WITNESS)
        val outputComplete =
            bool(at(witness, "output_run_set_checks/v9_k3_to_k9_comparison/k9_included")) == true
        val sourceWasStale = bool(at(witness, "k9_near_miss/changed_after_the_freeze")) == true
        assertTrue(
            "both facts must be recorded together: output complete=$outputComplete, " +
                "source changed after the freeze=$sourceWasStale",
            outputComplete && sourceWasStale,
        )
        assertNotNull(
            "the witness must say how completeness was achieved",
            str(at(witness, "k9_near_miss/but_completeness_was_achieved_by")),
        )
    }

    @Test
    fun `C20 the v9 comparison validator checked fields and not the run set`() {
        val witness = obj(RUN_SET_WITNESS)
        val field = at(witness, "two_validities_kept_apart/enumerated_field_comparison_validity")
        val completeness = at(witness, "two_validities_kept_apart/run_set_completeness_validity")
        assertEquals(
            "the field verdict recorded must be the one v9 actually wrote",
            str(obj(V9_COMPARISON_VALIDATION)?.get("verdict")),
            str(at(field as? JsonObject, "verdict_recorded_by_v9")),
        )
        assertTrue(
            "the witness must record that the registry was never consulted by the validator",
            bool(at(completeness as? JsonObject,
                    "registry_was_never_consulted_by_the_validator")) == true,
        )
    }

    // =============================================================================================
    // Run-set closure — what v10 must be
    // =============================================================================================

    @Test
    fun `C06 K7 is declared not_run_hold and carried by the host comparison projection`() {
        val entry = registryRuns()["K7"]
        assertNotNull("the v10 registry must declare K7", entry)
        assertEquals("not_run_hold", str(entry?.get("lifecycle_state")))
        assertTrue(
            "K7 must be in the host comparison projection as a state, not dropped",
            projectionRuns("host_comparison_runs").contains("K7"),
        )
        assertTrue(
            "K7 must not be in host_runs_with_records",
            !projectionRuns("host_runs_with_records").contains("K7"),
        )
    }

    @Test
    fun `C07 the device official projection resolves to exactly D10`() {
        assertEquals(listOf("D10"), projectionRuns("device_official_runs"))
        assertEquals("device_official", str(registryRuns()["D10"]?.get("run_kind")))
    }

    @Test
    fun `C08 the device smoke projection resolves to exactly V10_SMOKE`() {
        assertEquals(listOf("V10_SMOKE"), projectionRuns("device_smoke_runs"))
        assertEquals("device_smoke", str(registryRuns()["V10_SMOKE"]?.get("run_kind")))
    }

    @Test
    fun `C09 no v10 operational consumer holds a manual run list`() {
        val scan = obj(ENUMERATION_SCAN)
        assertNotNull("the v10 enumeration scan must be in the tree", scan)
        assertEquals(
            "operational manual enumerations must be zero",
            0, int(scan?.get("operational_manual_enumeration_count")),
        )
    }

    @Test
    fun `C10 every consumer's actual output run set equals its projection`() {
        val validation = obj(CONSUMER_RUN_SETS)
        assertNotNull("the consumer run-set validation must be in the tree", validation)
        assertEquals("missing runs", 0, int(at(validation, "totals/missing")))
        assertEquals("unexpected runs", 0, int(at(validation, "totals/unexpected")))
        assertTrue(
            "every consumer with an output must have been compared",
            (int(at(validation, "totals/consumers_compared")) ?: 0) > 0,
        )
    }

    @Test
    fun `C11 a duplicate run in an output is detected`() {
        assertTrue(
            "the run-set validator must report duplicates",
            bool(at(obj(CONSUMER_RUN_SETS), "detects/duplicate")) == true,
        )
        assertEquals(0, int(at(obj(CONSUMER_RUN_SETS), "totals/duplicate")))
    }

    @Test
    fun `C12 an unexpected run in an output is detected`() {
        assertTrue(
            "the run-set validator must report unexpected runs",
            bool(at(obj(CONSUMER_RUN_SETS), "detects/unexpected")) == true,
        )
    }

    @Test
    fun `C13 an order difference is detected where order is meaningful`() {
        assertTrue(
            "the run-set validator must compare order for ordered projections",
            bool(at(obj(CONSUMER_RUN_SETS), "detects/order_mismatch")) == true,
        )
        assertEquals(0, int(at(obj(CONSUMER_RUN_SETS), "totals/order_mismatch")))
        val ordered = projections().filterValues {
            str(it["ordering_semantics"]) == "ordered"
        }
        assertTrue("at least one projection must be ordered", ordered.isNotEmpty())
    }

    @Test
    fun `C14 an unknown run key is rejected`() {
        assertTrue(
            "the run-set validator must reject unknown run keys",
            bool(at(obj(CONSUMER_RUN_SETS), "detects/unknown_run")) == true,
        )
        assertEquals(0, int(at(obj(CONSUMER_RUN_SETS), "totals/unknown_run")))
    }

    @Test
    fun `C15 the consumer contract and the consumer inventory name the same consumers`() {
        val declared = consumers().keys.sorted()
        val inventory = obj(CONSUMER_INVENTORY)
        assertNotNull("the consumer inventory must be in the tree", inventory)
        val found = list(inventory?.get("consumer_ids")).sorted()
        assertEquals("declared and inventoried consumers must be the same set", declared, found)
        assertTrue("there must be consumers to inventory", declared.isNotEmpty())
    }

    @Test
    fun `C16 no consumer holds both a projection and a fallback list`() {
        val offenders = consumers().filter { (_, entry) ->
            str(entry["projection_id"]) != null && bool(entry["has_fallback_list"]) == true
        }.keys
        assertEquals("consumers with a fallback list beside a projection", emptySet<String>(),
                     offenders)
        assertTrue(
            "the contract must state that a fallback is a second authority",
            (str(obj(CONSUMER_CONTRACT)?.get("no_fallback_rule")) ?: "").isNotEmpty(),
        )
    }

    @Test
    fun `C17 every registry run belongs to at least one projection`() {
        val covered = projections().values.flatMap { list(it["runs"]) }.toSet()
        val orphans = registryRuns().keys.filterNot { covered.contains(it) }.sorted()
        assertEquals("runs in no projection", emptyList<String>(), orphans)
        assertEquals(
            "the registry must declare every required run",
            REQUIRED_RUNS.sorted(), registryRuns().keys.sorted(),
        )
    }

    @Test
    fun `C18 every projection is claimed by at least one consumer`() {
        val claimed = consumers().values.mapNotNull { str(it["projection_id"]) }.toSet()
        val orphans = projections().keys.filterNot { claimed.contains(it) }.sorted()
        assertEquals("projections no consumer reads", emptyList<String>(), orphans)
    }

    @Test
    fun `C19 every consumer names the projection it reads`() {
        val nameless = consumers().filterValues { str(it["projection_id"]) == null }.keys.sorted()
        assertEquals("consumers with no projection id", emptyList<String>(), nameless)
    }

    // =============================================================================================
    // Evidence lifecycle — what v9 actually did
    // =============================================================================================

    @Test
    fun `C21 the frozen source changed after the run is fixed as its own record`() {
        val record = obj(D1)
        assertNotNull("D1 must be its own record", record)
        assertEquals("FROZEN_SOURCE_CHANGED_AFTER_RUN", str(record?.get("code")))
        assertEquals("CHANGED_SINCE_THE_FREEZE", str(record?.get("digest_relationship")))
        assertTrue(
            "the reversibility proof must have been run, not asserted",
            bool(at(record, "reversible_check/reproduces_the_declared_digest")) == true,
        )
        assertTrue(
            "D1 must not be recorded as touching an authority artefact",
            bool(record?.get("touches_an_authority_artifact")) == false,
        )
    }

    @Test
    fun `C22 the post-run file added to a frozen root is fixed as its own record`() {
        val record = obj(D2)
        assertNotNull("D2 must be its own record", record)
        assertEquals("POST_RUN_FILE_ADDED_TO_FROZEN_ROOT", str(record?.get("code")))
        assertEquals("NOT_DECLARED_BY_THE_V9_FREEZE", str(record?.get("digest_relationship")))
        assertNotNull("D2 must name the frozen root it landed in",
                      str(record?.get("frozen_root_it_landed_in")))
        assertNotNull("D2 must name the lifecycle root it should have used",
                      str(record?.get("correct_lifecycle")))
    }

    @Test
    fun `C23 the overwritten pre-run audit is fixed as its own record and the loss is stated`() {
        val record = obj(D3)
        assertNotNull("D3 must be its own record", record)
        assertEquals("PRE_RUN_EVIDENCE_OVERWRITTEN", str(record?.get("code")))
        assertEquals(
            "the pre-run content must be recorded as unrecoverable, not glossed",
            false, bool(at(record, "content_loss/pre_run_content_recoverable_from_the_tree")),
        )
        assertNotNull("the surviving digest must be recorded",
                      str(at(record, "content_loss/surviving_digest")))
        val index = obj(DEVIATION_INDEX)
        assertEquals(
            "all three deviations must be indexed",
            3, (index?.get("records") as? JsonArray)?.size,
        )
    }

    // =============================================================================================
    // Evidence lifecycle — what v10 must be
    // =============================================================================================

    @Test
    fun `C24 no declared output name is shared by two stages`() {
        val outputs = declaredOutputs().map { it.second }
        assertTrue("there must be declared outputs", outputs.isNotEmpty())
        val duplicates = outputs.groupBy { it }.filterValues { it.size > 1 }.keys.sorted()
        assertEquals("output paths declared twice", emptyList<String>(), duplicates)
        val staged = outputs.filter { name ->
            name.contains("_pre_") || name.contains("_post_")
        }
        assertTrue("stage-bearing filenames must be used", staged.isNotEmpty())
    }

    @Test
    fun `C25 a casefold collision among declared outputs is refused`() {
        val outputs = declaredOutputs().map { it.second }
        assertEquals("casefold or normalization collisions", emptyList<List<String>>(),
                     collisions(outputs))
        assertTrue(
            "the lifecycle contract must declare a casefold policy",
            roots().values.all { str(it["casefold_policy"]) != null },
        )
    }

    @Test
    fun `C26 a Unicode normalization collision among declared outputs is refused`() {
        assertTrue(
            "the lifecycle contract must declare a normalization policy per root",
            roots().isNotEmpty() &&
                roots().values.all { str(it["unicode_normalization_policy"]) != null },
        )
        val validation = obj(LIFECYCLE_PATHS)
        assertEquals("normalization collisions found", 0,
                     int(at(validation, "totals/unicode_collisions")))
    }

    @Test
    fun `C27 every post-run output resolves outside every frozen root`() {
        val validation = obj(LIFECYCLE_PATHS)
        assertNotNull("the lifecycle path validation must be in the tree", validation)
        assertEquals("post-run writes into a frozen root", 0,
                     int(at(validation, "totals/post_run_into_frozen")))
        assertEquals(
            "the contract must separate every required root",
            REQUIRED_ROOTS.sorted(), roots().keys.sorted(),
        )
    }

    @Test
    fun `C28 move and delete inside a frozen root are forbidden`() {
        listOf("frozen_source_root", "frozen_pre_run_evidence_root").forEach { id ->
            val allowed = list(roots()[id]?.get("allowed_operations"))
            assertTrue("$id must not allow move", !allowed.contains("move"))
            assertTrue("$id must not allow delete", !allowed.contains("delete"))
        }
    }

    @Test
    fun `C29 regenerating the freeze after a mismatch is a declared violation`() {
        val contract = obj(LIFECYCLE_CONTRACT)
        assertNotNull("the lifecycle contract must be in the tree", contract)
        val forbidden = list(contract?.get("forbidden_remedies"))
        assertTrue(
            "retaking the freeze must be named as a forbidden remedy; found $forbidden",
            forbidden.any { it.contains("freeze") },
        )
        assertTrue(
            "moving a file to clear a mismatch must be named as a forbidden remedy",
            forbidden.any { it.contains("move") || it.contains("relocat") },
        )
    }

    @Test
    fun `C30 the emergency hold report has a root declared before the freeze`() {
        val root = roots()["emergency_hold_report_root"]
        assertNotNull("the emergency root must be declared", root)
        assertEquals("post_freeze", str(root?.get("phase")))
        assertTrue(
            "the emergency root must not be part of the frozen inputs",
            bool(root?.get("included_in_freeze")) == false,
        )
        assertEquals("create_new_only", str(root?.get("overwrite_policy")))
    }

    @Test
    fun `C31 delivery verification writes outside the bundle source`() {
        val external = roots()["external_delivery_verification_root"]
        val source = roots()["delivery_source_root"]
        assertNotNull("the external verification root must be declared", external)
        assertNotNull("the delivery source root must be declared", source)
        val externalPath = str(external?.get("path")) ?: ""
        val sourcePath = str(source?.get("path")) ?: ""
        assertTrue(
            "the external root must not be inside the bundle source root",
            externalPath.isNotEmpty() && !externalPath.startsWith("$sourcePath/"),
        )
        assertNotNull(
            "the contract must state why a zip cannot carry its own digest",
            str(obj(LIFECYCLE_CONTRACT)?.get("why_a_zip_cannot_contain_its_own_digest")),
        )
    }

    @Test
    fun `C32 every write-once root refuses to replace an existing file`() {
        val writeOnce = roots().filterValues { str(it["overwrite_policy"]) == "create_new_only" }
        assertTrue("there must be write-once roots", writeOnce.isNotEmpty())
        assertTrue(
            "a write-once root must declare atomic creation",
            writeOnce.values.all { str(it["atomicity"]) != null },
        )
        assertEquals(
            "the dry-run must have proven the refusal, not assumed it",
            true, bool(at(obj(DRY_RUN), "checks/rerun_refuses_overwrite")),
        )
    }

    @Test
    fun `C33 only the runner may write an authority artefact`() {
        val authority = roots()["official_run_authority_root"]
        assertNotNull("the authority root must be declared", authority)
        assertEquals(
            "only the runner writes the authority root",
            listOf("official_runner"), list(authority?.get("allowed_writers")),
        )
        assertEquals(
            "the dry-run must have proven no derived tool modified an authority artefact",
            0, int(at(obj(DRY_RUN), "totals/authority_modifications")),
        )
    }

    @Test
    fun `C34 the dry-run's declared paths are the real paths`() {
        val dryRun = obj(DRY_RUN)
        assertNotNull("the dry-run report must be in the tree", dryRun)
        assertEquals(
            "the dry-run inventory must match the real inventory path for path",
            emptyList<String>(), list(at(dryRun, "path_inventory_difference")),
        )
        assertTrue(
            "the only difference may be the root prefix",
            bool(at(dryRun, "checks/only_root_prefix_differs")) == true,
        )
    }

    @Test
    fun `C35 no root is broader than its declared scope`() {
        assertTrue("roots must be declared", roots().isNotEmpty())
        roots().forEach { (id, root) ->
            val path = str(root["path"]) ?: ""
            assertTrue("$id must declare a path", path.isNotEmpty())
            assertTrue("$id must not be the repository root", path != "." && path != "")
            assertTrue("$id must declare a maximum scope", str(root["maximum_scope"]) != null)
            assertTrue(
                "$id must declare an expected file inventory",
                root["expected_file_inventory"] != null,
            )
        }
        val validation = obj(LIFECYCLE_PATHS)
        assertEquals("roots broader than their declared scope", 0,
                     int(at(validation, "totals/over_broad_roots")))
    }

    @Test
    fun `C36 no source code lives under an excluded root`() {
        val validation = obj(LIFECYCLE_PATHS)
        assertNotNull("the lifecycle path validation must be in the tree", validation)
        assertEquals("source files under an excluded root", 0,
                     int(at(validation, "totals/source_under_excluded_root")))
    }

    // =============================================================================================
    // Not a case: the registry must be readable at all for any of the above to mean anything.
    // =============================================================================================

    @Test
    fun `the v10 registry declares every required run with its membership metadata`() {
        val runs = registryRuns()
        assertEquals(REQUIRED_RUNS.sorted(), runs.keys.sorted())
        runs.forEach { (key, entry) ->
            MEMBERSHIP_FIELDS.forEach { field ->
                assertTrue("$key must declare $field", entry[field] != null)
            }
        }
    }
}
