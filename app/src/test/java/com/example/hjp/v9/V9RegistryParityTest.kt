package com.example.hjp.v9

import com.example.hjp.v9.V9Contracts.CROSS_RUN
import com.example.hjp.v9.V9Contracts.GENERATED_IDENTITY
import com.example.hjp.v9.V9Contracts.K8_RESULT
import com.example.hjp.v9.V9Contracts.PARITY
import com.example.hjp.v9.V9Contracts.REGISTRY
import com.example.hjp.v9.V9Contracts.RUNNER_IDENTITY
import com.example.hjp.v9.V9Contracts.SOURCE_IDENTITY
import com.example.hjp.v9.V9Contracts.at
import com.example.hjp.v9.V9Contracts.bool
import com.example.hjp.v9.V9Contracts.int
import com.example.hjp.v9.V9Contracts.list
import com.example.hjp.v9.V9Contracts.obj
import com.example.hjp.v9.V9Contracts.registryRuns
import com.example.hjp.v9.V9Contracts.requireObj
import com.example.hjp.v9.V9Contracts.sha256
import com.example.hjp.v9.V9Contracts.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is the authority, and everything else agrees with it — checked from the JVM side.
 *
 * The Python validator does this over the whole set and proves the check bites with four drills.
 * This does it again from Kotlin, over the constants the runner actually compiles against, because
 * a parity check that only ever runs in one language has shown that one implementation agrees with
 * itself. That is the shape of the v8 defect, one level up.
 */
class V9RegistryParityTest {

    @Test
    fun `the generated constants are the registry's values`() {
        val runs = registryRuns()
        assertTrue("the registry declares no runs", runs.isNotEmpty())

        // V9RunIdentity is generated from the registry. Comparing the compiled constants back
        // against the registry closes the loop: the generator could be wrong, and this would catch
        // it, because this reads the object the runner actually links against.
        assertEquals("the compiled registry version is not the registry's",
            str(requireObj(REGISTRY)["version"]), V9RunIdentity.REGISTRY_VERSION)
        assertEquals(str(requireObj(REGISTRY)["schema"]), V9RunIdentity.REGISTRY_SCHEMA)

        assertEquals(V9RunIdentity.K9.hostRunId, str(runs.getValue("K9")["host_run_id"]))
        assertEquals(V9RunIdentity.K9.hostResultSchema,
            str(runs.getValue("K9")["host_result_schema"]))
        assertEquals(V9RunIdentity.K9.rawTurnSchema, str(runs.getValue("K9")["raw_turn_schema"]))
        assertEquals(V9RunIdentity.K9.hostOutputNamespace,
            str(runs.getValue("K9")["host_output_namespace"]))
        assertEquals(V9RunIdentity.K9.metricSourcePath,
            str(runs.getValue("K9")["metric_source_path"]))
        assertEquals(V9RunIdentity.K9.runtimeMode, str(runs.getValue("K9")["runtime_mode"]))

        assertEquals(V9RunIdentity.K8.hostResultSchema,
            str(runs.getValue("K8")["host_result_schema"]))
        assertEquals(V9RunIdentity.D9.deviceRunId, str(runs.getValue("D9")["device_run_id"]))
        assertEquals(V9RunIdentity.SMOKE.smokeRunId,
            str(runs.getValue("V9_SMOKE")["smoke_run_id"]))
    }

    @Test
    fun `the two generated copies are byte-identical`() {
        assertEquals(
            "the generated identity file in the source tree and the canonical one beside the " +
                "generator differ. Two copies of one generated fact must come from one render, or " +
                "they are two hand-maintained copies wearing a generator's name",
            sha256(GENERATED_IDENTITY), sha256(SOURCE_IDENTITY),
        )
    }

    @Test
    fun `K8's schema agrees across the record, the registry, the contract and the runner`() {
        val record = str(requireObj(K8_RESULT)["schema"])
        val registry = str(registryRuns().getValue("K8")["host_result_schema"])
        val contract = str(at(requireObj(CROSS_RUN), "runs/K8/expected_result_schema"))
        val runner = V9RunIdentity.K8.hostResultSchema

        assertEquals("the record and the registry disagree", record, registry)
        assertEquals("the registry and the contract disagree", registry, contract)
        assertEquals("the registry and the generated constants disagree", registry, runner)
        assertEquals(
            "this is the exact triple that disagreed in v8, and it must now be one value",
            1, setOf(record, registry, contract, runner).size,
        )
        assertEquals("ryeong_v8_official_result/v1", record)
    }

    @Test
    fun `the contract registers every schema a declared run publishes`() {
        val contract = requireObj(CROSS_RUN)
        val registered = (contract["record_schemas"] as JsonObject).keys
        registryRuns().forEach { (name, entry) ->
            val schema = str(entry["host_result_schema"]) ?: return@forEach
            assertTrue(
                "$name publishes $schema and the contract does not register it. That is the v8 " +
                    "defect exactly: a run whose record the reader is required to refuse",
                registered.contains(schema),
            )
        }
    }

    @Test
    fun `the python parity validator ran clean and its drills all fired`() {
        val report = obj(PARITY)
        assertNotNull("the registry parity report is not in the tree: $PARITY", report)
        assertEquals("REGISTRY PARITY", str(report!!["verdict"]))
        assertEquals("parity disagreements: " + report["disagreements"],
            0, int(report["disagreement_count"]))
        assertEquals("every declared run must be compared", 9, int(report["runs_compared"]))
        assertEquals("no field may go untested",
            emptyList<String>(), list(report["fields_never_compared"]))

        val drills = report["single_source_drift_drills"] as? JsonObject
        assertNotNull("parity that has never failed has not been shown to work", drills)
        listOf("runner", "contract", "reader", "registry").forEach { side ->
            val drill = drills!![side]?.jsonObject
            assertNotNull("no drill for moving the $side alone", drill)
            assertEquals("moving the $side alone was not detected", true, bool(drill!!["detected"]))
            assertEquals("REGISTRY_PARITY_MISMATCH", str(drill["failure_code"]))
        }
        assertEquals(true, bool(report["all_drills_detected"]))
    }

    @Test
    fun `the runner identity was extracted at the strongest evidence available`() {
        val identity = obj(RUNNER_IDENTITY)
        assertNotNull("the runner identity extract is not in the tree", identity)
        val runs = identity!!["runs"] as JsonObject

        // A completed run's record outranks every other source. Accepting a source constant for a
        // run that produced a record would be documenting an intention.
        listOf("K3", "K4", "K5", "K6", "K8").forEach { name ->
            val entry = runs[name]!!.jsonObject
            assertEquals(
                "$name produced a record, so its identity must come from that record and not from " +
                    "a source constant",
                "ACTUAL_HISTORICAL_RECORD", str(entry["evidence_kind"]),
            )
            assertEquals(1, int(entry["evidence_strength"]))
        }
        val k7 = runs["K7"]!!.jsonObject
        assertEquals("NO_RECORD_AND_NONE_EXPECTED", str(k7["evidence_kind"]))
        assertTrue("K9 has not run, so its identity cannot come from a record",
            str(runs["K9"]!!.jsonObject["evidence_kind"]) != "ACTUAL_HISTORICAL_RECORD")
        assertEquals("the generated manifest must corroborate without contradicting",
            emptyList<String>(), list(identity["generated_manifest_disagreements"]))
    }

    @Test
    fun `K7 is a state and carries no metrics`() {
        val k7 = registryRuns().getValue("K7")
        assertEquals("NOT_RUN_DUE_TO_PREFLIGHT_HOLD", str(k7["historical_status"]))
        assertEquals(0, int(k7["invocations"]))
        assertEquals(false, bool(k7["published"]))
        assertTrue("K7 has a historical result path and never produced one",
            str(k7["historical_result_path"]).isNullOrBlank())
        assertTrue("K7 has a host result schema and never wrote a record",
            str(k7["host_result_schema"]).isNullOrBlank())
        assertTrue("the registry does not forbid copying K6's metrics into K7",
            list(k7["forbidden"]).any { it.contains("K6") })
    }

    @Test
    fun `K8's two verdicts are both recorded and neither overwrites the other`() {
        val k8 = registryRuns().getValue("K8")
        assertEquals("VALID BASELINE", str(k8["run_local_validity"]))
        assertEquals("HOLD BEFORE V8 DEVICE PREFLIGHT", str(k8["version_level_verdict"]))
        assertEquals("UNREADABLE", str(k8["readability_under_frozen_v8_contract"]))
        assertEquals("READABLE", str(k8["readability_under_v9_contract"]))
        assertTrue("the registry does not explain why these are separate",
            str(k8["three_verdicts_note"])?.contains("three different questions") == true)
    }
}
