package com.example.hjp.v9char

import com.example.hjp.v9char.V9CharacterizationFixture.GENERATED_IDENTITY
import com.example.hjp.v9char.V9CharacterizationFixture.IDENTITY_FIELDS
import com.example.hjp.v9char.V9CharacterizationFixture.K8_HOST_VALIDITY
import com.example.hjp.v9char.V9CharacterizationFixture.K8_READER_COMPARISON
import com.example.hjp.v9char.V9CharacterizationFixture.K8_RESULT
import com.example.hjp.v9char.V9CharacterizationFixture.K8_STATUS
import com.example.hjp.v9char.V9CharacterizationFixture.PARITY_REPORT
import com.example.hjp.v9char.V9CharacterizationFixture.REGISTRY
import com.example.hjp.v9char.V9CharacterizationFixture.REQUIRED_RUNS
import com.example.hjp.v9char.V9CharacterizationFixture.RUNNER_IDENTITY
import com.example.hjp.v9char.V9CharacterizationFixture.V8_COMPARISON
import com.example.hjp.v9char.V9CharacterizationFixture.V8_CONTRACT_RETARGET
import com.example.hjp.v9char.V9CharacterizationFixture.V8_CROSS_RUN_CONTRACT
import com.example.hjp.v9char.V9CharacterizationFixture.V8_RUNNER_RETARGET
import com.example.hjp.v9char.V9CharacterizationFixture.V9_CROSS_RUN_CONTRACT
import com.example.hjp.v9char.V9CharacterizationFixture.at
import com.example.hjp.v9char.V9CharacterizationFixture.bool
import com.example.hjp.v9char.V9CharacterizationFixture.compareToRegistry
import com.example.hjp.v9char.V9CharacterizationFixture.contractIdentity
import com.example.hjp.v9char.V9CharacterizationFixture.historicalIdentity
import com.example.hjp.v9char.V9CharacterizationFixture.int
import com.example.hjp.v9char.V9CharacterizationFixture.list
import com.example.hjp.v9char.V9CharacterizationFixture.obj
import com.example.hjp.v9char.V9CharacterizationFixture.present
import com.example.hjp.v9char.V9CharacterizationFixture.registryRuns
import com.example.hjp.v9char.V9CharacterizationFixture.requireObj
import com.example.hjp.v9char.V9CharacterizationFixture.str
import com.example.hjp.v9char.V9CharacterizationFixture.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v8 schema drift, as a test — and as an arrangement rather than a string.
 *
 * v8's cross-run contract could not read the run v8's own runner produced. Neither artefact was
 * internally wrong. `retarget_host_runner_v8.py` declared the result-schema substitution and
 * reproduced its target byte for byte; `retarget_contracts_v8.py` did not declare it and also
 * reproduced its target byte for byte. Byte-reproducibility of an artefact says nothing about
 * whether two artefacts still agree.
 *
 * Adding the missing pair to the contract would fix this instance and leave the shape untouched: two
 * independent lists, each self-consistent, waiting to drift the next time one of them moves. So the
 * cases below check the arrangement — one registry that owns run identity, others generated from or
 * checked against it, and parity asserted for every declared run rather than for the one that broke.
 *
 * Three verdicts about K8 are kept apart throughout, because collapsing any two of them loses
 * something true:
 *
 *     run-local validity                          VALID BASELINE
 *     readability under the frozen v8 contract     UNREADABLE
 *     v8 version readiness                        HOLD
 *
 * Case ids refer to `tools/ryeong_official_v9/contracts/characterization_cases_v9.json`, frozen at
 * RED and unmodified since.
 */
class V9RunIdentityCharacterizationTest {

    private fun registry(): JsonObject? = obj(REGISTRY)

    // ==============================================================================================
    // C01 … C06 — the frozen v8 record. These pass at RED: they describe what already exists.
    // ==============================================================================================

    @Test
    fun `C01 the frozen v8 contract declares K8's expected schema as v7`() {
        val contract = requireObj(V8_CROSS_RUN_CONTRACT)
        val k8 = at(contract, "runs/K8") as JsonObject
        assertEquals(
            "the frozen v8 contract no longer declares the v7 schema for K8; the historical " +
                "record has moved",
            "ryeong_v7_official_result/v1", str(k8["expected_result_schema"]),
        )
        assertEquals(
            "the raw schema did not drift and must still read v6",
            "ryeong_v6_raw_turn/v1", str(k8["expected_raw_schema"]),
        )
        val registered = (contract["record_schemas"] as JsonObject).keys
        assertTrue(
            "ryeong_v8_official_result/v1 is registered in the v8 contract after all, which would " +
                "mean there was nothing for the reader to refuse",
            "ryeong_v8_official_result/v1" !in registered,
        )
    }

    @Test
    fun `C02 the K8 record the v8 runner produced declares the v8 schema`() {
        val result = requireObj(K8_RESULT)
        assertEquals("ryeong_v8_official_result/v1", str(result["schema"]))
        assertEquals(
            "RYEONG_PRODUCTION_COMPATIBILITY_V8_RUN_K8_JVM_KEYWORD_HOST_BASELINE",
            str(result["run_id"]),
        )
    }

    @Test
    fun `C03 the frozen v8 comparison refused K8, fail-closed`() {
        val comparison = requireObj(V8_COMPARISON)
        val k8 = at(comparison, "run_reads/K8") as JsonObject
        assertEquals("K8 is readable under the v8 contract, so there was no drift to correct",
            false, bool(k8["readable"]))
        assertEquals(false, bool(k8["comparable"]))
        assertEquals(
            "the refusal codes are not the two fail-closed ones",
            listOf("UNSUPPORTED_SCHEMA", "UNREADABLE_SCHEMA").sorted(),
            list(k8["failure_codes"]).sorted(),
        )
        assertTrue("K8 is listed as present in the v8 comparison",
            !list(comparison["runs_present"]).contains("K8"))
    }

    @Test
    fun `C04 K8's own four readers agreed and the run is valid run-locally`() {
        val validity = requireObj(K8_HOST_VALIDITY)
        assertEquals(
            "K8's run-local validity has changed. The schema drift is about reading the run, not " +
                "about the run",
            "VALID BASELINE", str(validity["validity_verdict"]),
        )
        assertEquals(0, int(validity["validity_failure_count"]))

        val comparison = requireObj(K8_READER_COMPARISON)
        assertEquals("the four readers no longer agree", true, bool(comparison["agrees"]))
        assertEquals(0, int(comparison["disagreement_count"]))
        assertEquals(1452, int(comparison["fields_compared"]))
        assertEquals(4, list(comparison["readers"]).size)

        val status = requireObj(K8_STATUS)
        assertEquals(1, int(status["invocations"]))
        assertEquals(true, bool(status["completed"]))
        assertEquals(false, bool(status["partial"]))
    }

    @Test
    fun `C05 the runner's retarget list declares the schema move`() {
        val script = text(V8_RUNNER_RETARGET)
        assertNotNull("the frozen v8 runner retarget script is not in the tree", script)
        assertTrue(
            "retarget_host_runner_v8.py no longer declares the result-schema substitution",
            script!!.contains("ryeong_v7_official_result/v1")
                && script.contains("ryeong_v8_official_result/v1"),
        )
    }

    @Test
    fun `C06 the contract's retarget list does not declare the schema move`() {
        val script = text(V8_CONTRACT_RETARGET)
        assertNotNull("the frozen v8 contract retarget script is not in the tree", script)
        assertTrue(
            "retarget_contracts_v8.py declares the result-schema substitution after all, which " +
                "would mean the drift had some other cause",
            !script!!.contains("ryeong_v8_official_result/v1"),
        )
    }

    // ==============================================================================================
    // C07, C08, C20, C21 — the registry's shape
    // ==============================================================================================

    @Test
    fun `C07 the run identity registry exists and carries the required shape`() {
        val document = registry()
        assertNotNull("the v9 run identity registry is not in the tree: $REGISTRY", document)
        listOf("schema", "version", "runs", "required_fields_per_run", "authority").forEach { field ->
            assertTrue("the registry does not carry $field", document!!.containsKey(field))
        }
        assertTrue(
            "the registry does not declare itself the authority for run identity, which is the " +
                "whole point of having one",
            str(document!!["authority"])?.isNotBlank() == true,
        )
    }

    @Test
    fun `C08 the registry declares K8 with the schema the record actually publishes`() {
        val runs = registryRuns()
        assertTrue("the registry has no K8 entry", runs.containsKey("K8"))
        val k8 = runs.getValue("K8")
        assertEquals(
            "the registry's K8 result schema is not the one the record declares. A registry that " +
                "repeats the contract's mistake has moved the defect rather than fixed it",
            "ryeong_v8_official_result/v1", str(k8["host_result_schema"]),
        )
        assertEquals("ryeong_v6_raw_turn/v1", str(k8["raw_turn_schema"]))
        assertEquals(true, bool(k8["published"]))
        assertEquals("VALID BASELINE", str(k8["run_local_validity"]))
        assertEquals(
            "the registry must record that v8 as a version held, without touching K8's own validity",
            "HOLD BEFORE V8 DEVICE PREFLIGHT", str(k8["version_level_verdict"]),
        )
    }

    @Test
    fun `C21 the registry declares every run the arrangement names`() {
        val runs = registryRuns()
        val missing = REQUIRED_RUNS.filterNot { runs.containsKey(it) }
        assertEquals("the registry is missing declared runs: $missing", emptyList<String>(), missing)

        val k7 = runs["K7"]
        assertNotNull("the registry has no K7 entry", k7)
        assertEquals("K7 must be carried as a state, not as a run with missing metrics",
            "NOT_RUN_DUE_TO_PREFLIGHT_HOLD", str(k7!!["historical_status"]))
        assertEquals(0, int(k7["invocations"]))
        assertEquals(false, bool(k7["published"]))
        assertTrue(
            "K7 has a historical result path. It was never invoked and produced nothing",
            str(k7["historical_result_path"]).isNullOrBlank(),
        )
    }

    @Test
    fun `C20 no run is declared twice and no identity is shared between runs`() {
        val runs = registryRuns()
        assertTrue("the registry is empty", runs.isNotEmpty())

        fun collect(field: String): List<String> =
            runs.values.mapNotNull { str(it[field]) }.filter { it.isNotBlank() }

        listOf("host_run_id", "device_run_id", "smoke_run_id").forEach { field ->
            val values = collect(field)
            assertEquals("two runs share a $field: $values", values.size, values.distinct().size)
        }
        listOf("host_output_namespace", "device_official_namespace",
            "device_smoke_namespace").forEach { field ->
            val values = collect(field)
            assertEquals("two runs share a $field: $values", values.size, values.distinct().size)
        }
        listOf("host_invocation_marker", "device_invocation_marker",
            "smoke_invocation_marker").forEach { field ->
            val values = collect(field)
            assertEquals("two runs share a $field: $values", values.size, values.distinct().size)
        }
    }

    // ==============================================================================================
    // C09 … C19 — parity, per field, for every declared run
    // ==============================================================================================

    @Test
    fun `C09 the registry agrees with the runner on the host result schema`() {
        val runnerIdentity = obj(RUNNER_IDENTITY)
        assertNotNull(
            "the runner identity extract is not in the tree; run " +
                "tools/ryeong_official_v9/extract_runner_identity_v9.py: $RUNNER_IDENTITY",
            runnerIdentity,
        )
        val runs = (runnerIdentity!!["runs"] as? JsonObject)?.mapNotNull { (key, value) ->
            (value as? JsonObject)?.let { entry ->
                key to IDENTITY_FIELDS.associateWith { str(entry[it]) }
                    .filterValues { it != null }
            }
        }?.toMap().orEmpty()
        assertTrue("the runner identity extract declares no runs", runs.isNotEmpty())
        val disagreements = compareToRegistry(runs, "runner",
            listOf("host_result_schema"))
        assertEquals("registry and runner disagree on the host result schema: $disagreements",
            emptyList<Any>(), disagreements)
    }

    @Test
    fun `C10 the registry agrees with the v9 contract on the host result schema`() {
        assertTrue("the v9 cross-run contract is not in the tree", present(V9_CROSS_RUN_CONTRACT))
        val disagreements = compareToRegistry(contractIdentity(), "contract",
            listOf("host_result_schema"))
        assertEquals("registry and contract disagree on the host result schema: $disagreements",
            emptyList<Any>(), disagreements)
    }

    @Test
    fun `C11 the registry agrees with the actual historical records`() {
        assertTrue("the registry is not in the tree", present(REGISTRY))
        val historical = historicalIdentity()
        assertTrue(
            "no historical record was resolved. The registry must pin the actual result record of " +
                "every run that produced one — a registry checked only against source constants " +
                "has documented an intention",
            historical.isNotEmpty(),
        )
        assertTrue("K8's historical record was not resolved", historical.containsKey("K8"))
        val disagreements = compareToRegistry(historical, "historical_record",
            listOf("host_result_schema", "host_run_id"))
        assertEquals(
            "the registry disagrees with a record that actually exists: $disagreements",
            emptyList<Any>(), disagreements,
        )
    }

    @Test
    fun `C12 to C19 every identity field agrees across registry, runner and contract`() {
        val runnerIdentity = obj(RUNNER_IDENTITY)
        assertNotNull("the runner identity extract is not in the tree", runnerIdentity)
        assertTrue("the v9 cross-run contract is not in the tree", present(V9_CROSS_RUN_CONTRACT))

        val runnerRuns = (runnerIdentity!!["runs"] as? JsonObject)?.mapNotNull { (key, value) ->
            (value as? JsonObject)?.let { entry ->
                key to IDENTITY_FIELDS.associateWith { str(entry[it]) }.filterValues { it != null }
            }
        }?.toMap().orEmpty()

        val all = compareToRegistry(runnerRuns, "runner") +
            compareToRegistry(contractIdentity(), "contract") +
            compareToRegistry(historicalIdentity(), "historical_record",
                listOf("host_result_schema", "host_run_id"))
        assertEquals("identity disagreements: $all", emptyList<Any>(), all)

        // Each field named by the case table must actually have been compared for at least one run,
        // or the parity check is passing by never asking.
        val asked = mutableSetOf<String>()
        listOf(runnerRuns, contractIdentity()).forEach { side ->
            side.values.forEach { values -> asked += values.keys }
        }
        listOf("raw_turn_schema", "metric_source_path", "host_run_id", "host_output_namespace",
            "runtime_mode").forEach { field ->
            assertTrue("no side declared $field, so parity on it was never tested",
                asked.contains(field))
        }
    }

    // ==============================================================================================
    // C22, C23 — the reader fails closed
    // ==============================================================================================

    @Test
    fun `C22 an unregistered schema is refused rather than inferred`() {
        val contract = obj(V9_CROSS_RUN_CONTRACT)
        assertNotNull("the v9 cross-run contract is not in the tree", contract)
        val codes = contract!!["failure_code_meanings"] as? JsonObject
        assertNotNull("the contract declares no failure code meanings", codes)
        assertTrue("UNSUPPORTED_SCHEMA is not a declared failure code",
            codes!!.containsKey("UNSUPPORTED_SCHEMA"))
        assertTrue("UNREADABLE_SCHEMA is not a declared failure code",
            codes.containsKey("UNREADABLE_SCHEMA"))

        val policy = contract["schema_admission_policy"] as? JsonObject
        assertNotNull(
            "the contract declares no schema admission policy. Without one, 'refuse an unknown " +
                "schema' is a property of the current code rather than of the contract",
            policy,
        )
        assertEquals("the policy must be fail-closed",
            true, bool(policy!!["fail_closed"]))
        assertEquals(
            "the policy must forbid inferring a metric location from a record's shape",
            true, bool(policy["infer_from_shape_forbidden"]),
        )
        assertEquals("the policy must forbid falling back to a previous version's schema",
            true, bool(policy["fallback_to_previous_version_forbidden"]))
    }

    @Test
    fun `C23 an unsupported contract or registry version is refused`() {
        val contract = obj(V9_CROSS_RUN_CONTRACT)
        assertNotNull("the v9 cross-run contract is not in the tree", contract)
        val policy = contract!!["schema_admission_policy"] as JsonObject
        assertTrue("UNSUPPORTED_VERSION is not declared",
            list(policy["failure_codes"]).contains("UNSUPPORTED_VERSION"))
        val registryDocument = registry()
        assertNotNull("the registry is not in the tree", registryDocument)
        assertTrue("the registry declares no schema version of its own",
            str(registryDocument!!["schema"])?.isNotBlank() == true)
        assertEquals(
            "the contract must pin the registry version it was generated from, or the two can " +
                "drift without anything noticing — which is the whole defect",
            str(registryDocument["version"]), str(contract["generated_from_registry_version"]),
        )
    }

    // ==============================================================================================
    // C24 … C27 — one artefact moves and the others do not
    // ==============================================================================================

    @Test
    fun `C24 to C27 moving any single artefact alone is detected`() {
        val report = obj(PARITY_REPORT)
        assertNotNull(
            "the registry parity report is not in the tree; run " +
                "tools/ryeong_official_v9/verify_registry_parity_v9.py: $PARITY_REPORT",
            report,
        )
        val drills = report!!["single_source_drift_drills"] as? JsonObject
        assertNotNull(
            "the parity report records no single-source drift drills. Parity that has never been " +
                "shown to fail has not been shown to work, and this is the exact failure v8 hit",
            drills,
        )
        listOf("runner", "contract", "reader", "registry").forEach { side ->
            val drill = drills!![side]?.jsonObject
            assertNotNull("no drill for moving the $side alone", drill)
            assertEquals(
                "moving the $side alone was not detected. That is the v8 defect, in the direction " +
                    "of the $side",
                true, bool(drill!!["detected"]),
            )
            assertEquals("REGISTRY_PARITY_MISMATCH", str(drill["failure_code"]))
        }
        assertEquals("the parity report itself must be clean for the unmutated tree",
            0, int(report["disagreement_count"]))
        assertEquals("REGISTRY PARITY", str(report["verdict"]))
    }

    @Test
    fun `the generated identity file is what the registry says it should be`() {
        assertTrue(
            "the generated Kotlin identity file is not in the tree: $GENERATED_IDENTITY. The point " +
                "of generating it is that the runner cannot hold a constant the registry does not",
            present(GENERATED_IDENTITY),
        )
        val document = registry()
        assertNotNull("the registry is not in the tree", document)
        val generated = text(GENERATED_IDENTITY)!!
        val k9 = registryRuns()["K9"]
        assertNotNull("the registry has no K9 entry", k9)
        listOf("host_run_id", "host_result_schema", "raw_turn_schema",
            "host_output_namespace").forEach { field ->
            val value = str(k9!![field])
            assertTrue(
                "the generated identity file does not carry K9's $field ($value)",
                value != null && generated.contains(value),
            )
        }
    }
}
