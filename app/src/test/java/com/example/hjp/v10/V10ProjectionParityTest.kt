package com.example.hjp.v10

import com.example.hjp.v10.V10Contracts.CROSS_RUN
import com.example.hjp.v10.V10Contracts.GENERATED_IDENTITY
import com.example.hjp.v10.V10Contracts.PROJECTIONS
import com.example.hjp.v10.V10Contracts.REGISTRY
import com.example.hjp.v10.V10Contracts.SOURCE_IDENTITY
import com.example.hjp.v10.V10Contracts.at
import com.example.hjp.v10.V10Contracts.bool
import com.example.hjp.v10.V10Contracts.int
import com.example.hjp.v10.V10Contracts.list
import com.example.hjp.v10.V10Contracts.obj
import com.example.hjp.v10.V10Contracts.registryRuns
import com.example.hjp.v10.V10Contracts.requireObj
import com.example.hjp.v10.V10Contracts.sha256
import com.example.hjp.v10.V10Contracts.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four sides, one registry: identity fields, and the run set itself.
 *
 * v9 built the first half of this and it worked — nine runs, seventeen fields, no disagreement. What
 * it did not build is the second half, and the second half is why v10 exists: for each side, does it
 * cover the runs the registry says it must? A side can agree about every field of every run it
 * declares and still be missing a run entirely, which is exactly what a driver named for K9 with a
 * list ending at K8 does.
 */
class V10ProjectionParityTest {

    private val identityFields = listOf(
        "host_run_id", "device_run_id", "smoke_run_id",
        "host_result_schema", "raw_turn_schema", "device_result_schema",
        "host_output_namespace", "device_official_namespace", "device_smoke_namespace",
        "invocation_marker", "metric_source_path", "runtime_mode",
        "ordinal", "run_kind", "lifecycle_state",
    )

    private fun kotlinIdentity(runKey: String): Map<String, String?> {
        fun of(o: Any): Map<String, String?> = when (o) {
            is V10RunIdentityAccessor -> o.fields()
            else -> emptyMap()
        }
        return of(accessorFor(runKey))
    }

    // The generated file is a set of objects, not a map, so the accessor is written out once here
    // rather than reached for by reflection. Reflection would silently return nothing if a field
    // were renamed, which is the failure this suite exists to catch.
    private interface V10RunIdentityAccessor { fun fields(): Map<String, String?> }

    private fun accessorFor(runKey: String): V10RunIdentityAccessor = when (runKey) {
        "K3" -> object : V10RunIdentityAccessor {
            override fun fields() = mapOf(
                "host_run_id" to V10RunIdentity.K3.HOST_RUN_ID,
                "host_result_schema" to V10RunIdentity.K3.HOST_RESULT_SCHEMA,
                "host_output_namespace" to V10RunIdentity.K3.HOST_OUTPUT_NAMESPACE,
                "runtime_mode" to V10RunIdentity.K3.RUNTIME_MODE,
                "run_kind" to V10RunIdentity.K3.RUN_KIND,
                "lifecycle_state" to V10RunIdentity.K3.LIFECYCLE_STATE,
                "ordinal" to V10RunIdentity.K3.ORDINAL.toString())
        }
        "K4" -> object : V10RunIdentityAccessor {
            override fun fields() = mapOf(
                "host_run_id" to V10RunIdentity.K4.HOST_RUN_ID,
                "host_result_schema" to V10RunIdentity.K4.HOST_RESULT_SCHEMA,
                "host_output_namespace" to V10RunIdentity.K4.HOST_OUTPUT_NAMESPACE,
                "runtime_mode" to V10RunIdentity.K4.RUNTIME_MODE,
                "run_kind" to V10RunIdentity.K4.RUN_KIND,
                "lifecycle_state" to V10RunIdentity.K4.LIFECYCLE_STATE,
                "ordinal" to V10RunIdentity.K4.ORDINAL.toString())
        }
        "K5" -> object : V10RunIdentityAccessor {
            override fun fields() = mapOf(
                "host_run_id" to V10RunIdentity.K5.HOST_RUN_ID,
                "host_result_schema" to V10RunIdentity.K5.HOST_RESULT_SCHEMA,
                "host_output_namespace" to V10RunIdentity.K5.HOST_OUTPUT_NAMESPACE,
                "runtime_mode" to V10RunIdentity.K5.RUNTIME_MODE,
                "run_kind" to V10RunIdentity.K5.RUN_KIND,
                "lifecycle_state" to V10RunIdentity.K5.LIFECYCLE_STATE,
                "ordinal" to V10RunIdentity.K5.ORDINAL.toString())
        }
        "K6" -> object : V10RunIdentityAccessor {
            override fun fields() = mapOf(
                "host_run_id" to V10RunIdentity.K6.HOST_RUN_ID,
                "host_result_schema" to V10RunIdentity.K6.HOST_RESULT_SCHEMA,
                "raw_turn_schema" to V10RunIdentity.K6.RAW_TURN_SCHEMA,
                "host_output_namespace" to V10RunIdentity.K6.HOST_OUTPUT_NAMESPACE,
                "runtime_mode" to V10RunIdentity.K6.RUNTIME_MODE,
                "run_kind" to V10RunIdentity.K6.RUN_KIND,
                "lifecycle_state" to V10RunIdentity.K6.LIFECYCLE_STATE,
                "ordinal" to V10RunIdentity.K6.ORDINAL.toString())
        }
        "K7" -> object : V10RunIdentityAccessor {
            override fun fields() = mapOf(
                "run_kind" to V10RunIdentity.K7.RUN_KIND,
                "lifecycle_state" to V10RunIdentity.K7.LIFECYCLE_STATE,
                "ordinal" to V10RunIdentity.K7.ORDINAL.toString())
        }
        "K8" -> object : V10RunIdentityAccessor {
            override fun fields() = mapOf(
                "host_run_id" to V10RunIdentity.K8.HOST_RUN_ID,
                "host_result_schema" to V10RunIdentity.K8.HOST_RESULT_SCHEMA,
                "raw_turn_schema" to V10RunIdentity.K8.RAW_TURN_SCHEMA,
                "host_output_namespace" to V10RunIdentity.K8.HOST_OUTPUT_NAMESPACE,
                "runtime_mode" to V10RunIdentity.K8.RUNTIME_MODE,
                "run_kind" to V10RunIdentity.K8.RUN_KIND,
                "lifecycle_state" to V10RunIdentity.K8.LIFECYCLE_STATE,
                "ordinal" to V10RunIdentity.K8.ORDINAL.toString())
        }
        "K9" -> object : V10RunIdentityAccessor {
            override fun fields() = mapOf(
                "host_run_id" to V10RunIdentity.K9.HOST_RUN_ID,
                "host_result_schema" to V10RunIdentity.K9.HOST_RESULT_SCHEMA,
                "raw_turn_schema" to V10RunIdentity.K9.RAW_TURN_SCHEMA,
                "host_output_namespace" to V10RunIdentity.K9.HOST_OUTPUT_NAMESPACE,
                "runtime_mode" to V10RunIdentity.K9.RUNTIME_MODE,
                "run_kind" to V10RunIdentity.K9.RUN_KIND,
                "lifecycle_state" to V10RunIdentity.K9.LIFECYCLE_STATE,
                "ordinal" to V10RunIdentity.K9.ORDINAL.toString())
        }
        "K10" -> object : V10RunIdentityAccessor {
            override fun fields() = mapOf(
                "host_run_id" to V10RunIdentity.K10.HOST_RUN_ID,
                "host_result_schema" to V10RunIdentity.K10.HOST_RESULT_SCHEMA,
                "raw_turn_schema" to V10RunIdentity.K10.RAW_TURN_SCHEMA,
                "host_output_namespace" to V10RunIdentity.K10.HOST_OUTPUT_NAMESPACE,
                "invocation_marker" to V10RunIdentity.K10.INVOCATION_MARKER,
                "runtime_mode" to V10RunIdentity.K10.RUNTIME_MODE,
                "run_kind" to V10RunIdentity.K10.RUN_KIND,
                "lifecycle_state" to V10RunIdentity.K10.LIFECYCLE_STATE,
                "ordinal" to V10RunIdentity.K10.ORDINAL.toString())
        }
        "D10" -> object : V10RunIdentityAccessor {
            override fun fields() = mapOf(
                "device_run_id" to V10RunIdentity.D10.DEVICE_RUN_ID,
                "device_result_schema" to V10RunIdentity.D10.DEVICE_RESULT_SCHEMA,
                "raw_turn_schema" to V10RunIdentity.D10.RAW_TURN_SCHEMA,
                "device_official_namespace" to V10RunIdentity.D10.DEVICE_OFFICIAL_NAMESPACE,
                "invocation_marker" to V10RunIdentity.D10.INVOCATION_MARKER,
                "run_kind" to V10RunIdentity.D10.RUN_KIND,
                "lifecycle_state" to V10RunIdentity.D10.LIFECYCLE_STATE,
                "ordinal" to V10RunIdentity.D10.ORDINAL.toString())
        }
        "V10_SMOKE" -> object : V10RunIdentityAccessor {
            override fun fields() = mapOf(
                "smoke_run_id" to V10RunIdentity.SMOKE.SMOKE_RUN_ID,
                "device_result_schema" to V10RunIdentity.SMOKE.DEVICE_RESULT_SCHEMA,
                "device_smoke_namespace" to V10RunIdentity.SMOKE.DEVICE_SMOKE_NAMESPACE,
                "invocation_marker" to V10RunIdentity.SMOKE.INVOCATION_MARKER,
                "run_kind" to V10RunIdentity.SMOKE.RUN_KIND,
                "lifecycle_state" to V10RunIdentity.SMOKE.LIFECYCLE_STATE,
                "ordinal" to V10RunIdentity.SMOKE.ORDINAL.toString())
        }
        else -> error("no accessor for $runKey — the generated file and this suite disagree about " +
                      "which runs exist, which is the disagreement this suite exists to catch")
    }

    @Test
    fun `the source tree and the canonical generated identity are byte-identical`() {
        assertEquals(
            "one render, two destinations — a difference means one was edited by hand",
            sha256(GENERATED_IDENTITY), sha256(SOURCE_IDENTITY),
        )
    }

    @Test
    fun `every registry field agrees with the compiled Kotlin constant`() {
        val runs = registryRuns()
        val disagreements = mutableListOf<String>()
        runs.forEach { (key, entry) ->
            val kotlin = kotlinIdentity(key)
            identityFields.forEach { field ->
                if (!kotlin.containsKey(field)) return@forEach
                val theirs = kotlin[field]
                val ours = if (field == "ordinal") int(entry[field])?.toString()
                           else str(entry[field])
                if (ours != theirs) disagreements += "$key.$field registry=$ours kotlin=$theirs"
            }
        }
        assertEquals("registry and compiled Kotlin must agree field by field",
                     emptyList<String>(), disagreements)
    }

    @Test
    fun `every registry field agrees with the generated cross-run contract`() {
        val contract = requireObj(CROSS_RUN)
        val runs = registryRuns()
        val disagreements = mutableListOf<String>()
        (contract["runs"] as? JsonObject)?.forEach { (key, value) ->
            val entry = runs[key] ?: run { disagreements += "$key: not in the registry"; return@forEach }
            val row = value as? JsonObject ?: return@forEach
            fun compare(field: String, contractField: String) {
                val ours = str(entry[field])
                val theirs = str(row[contractField])
                if (theirs != null && ours != theirs) {
                    disagreements += "$key.$field registry=$ours contract=$theirs"
                }
            }
            compare("host_run_id", "run_id")
            compare("host_result_schema", "expected_result_schema")
            compare("raw_turn_schema", "expected_raw_schema")
            compare("host_output_namespace", "directory")
            compare("runtime_mode", "runtime_mode")
            compare("run_local_validity", "validity")
        }
        assertEquals("registry and contract must agree field by field",
                     emptyList<String>(), disagreements)
    }

    @Test
    fun `every completed run's identity agrees with the record it actually wrote`() {
        val disagreements = mutableListOf<String>()
        registryRuns().forEach { (key, entry) ->
            val resultPath = str(entry["historical_result_path"]) ?: return@forEach
            val record = obj(resultPath) ?: run {
                disagreements += "$key: $resultPath is not readable"; return@forEach
            }
            val recordSchema = str(record["schema"])
            if (recordSchema != null && recordSchema != str(entry["host_result_schema"])) {
                disagreements += "$key.schema registry=${str(entry["host_result_schema"])} " +
                    "record=$recordSchema"
            }
            val statusPath = str(entry["historical_status_path"])
            val status = statusPath?.let { obj(it) }
            val recordedId = str(record["run_id"]) ?: str(status?.get("run_id"))
            if (recordedId != null && recordedId != str(entry["host_run_id"])) {
                disagreements += "$key.run_id registry=${str(entry["host_run_id"])} " +
                    "record=$recordedId"
            }
        }
        assertEquals("the record is the strongest evidence and the registry must match it",
                     emptyList<String>(), disagreements)
    }

    @Test
    fun `the run set itself agrees across the registry, the projection and the contract`() {
        val registry = requireObj(REGISTRY)
        val projectionsDoc = requireObj(PROJECTIONS)
        val contract = requireObj(CROSS_RUN)

        val registryHosts = registryRuns()
            .filterValues { str(it["run_kind"]) == "host" }
            .entries.sortedBy { int(it.value["ordinal"]) }.map { it.key }
        val projected = list(at(projectionsDoc, "projections/host_comparison_runs/runs"))
        val contractOrder = list(contract["run_order"])
        val kotlinOrder = V10RunIdentity.Projections.hostComparisonRuns

        assertEquals("registry hosts vs projection", registryHosts, projected)
        assertEquals("projection vs contract", projected, contractOrder)
        assertEquals("projection vs compiled Kotlin", projected, kotlinOrder)

        assertEquals("every side must carry the same registry digest",
                     1,
                     listOf(str(registry["registry_digest"]),
                            str(projectionsDoc["registry_digest"]),
                            str(contract["registry_digest"]),
                            V10RunIdentity.REGISTRY_DIGEST).distinct().size)
    }

    @Test
    fun `no side is missing a run another side declares`() {
        val sides = mapOf(
            "registry" to registryRuns().keys.sorted(),
            "projection_all_declared" to list(
                at(requireObj(PROJECTIONS), "projections/all_declared_runs/runs")).sorted(),
            "kotlin_all_declared" to V10RunIdentity.Projections.allDeclaredRuns.sorted(),
            "kotlin_run_order" to V10RunIdentity.runOrder.sorted(),
        )
        val union = sides.values.flatten().toSortedSet()
        val gaps = sides.filterValues { it.toSortedSet() != union }
            .map { (name, runs) -> "$name is missing ${union - runs.toSortedSet()}" }
        assertEquals("no side may be missing a run another side declares",
                     emptyList<String>(), gaps)
        assertTrue("the union must be the ten declared runs", union.size == 10)
    }

    @Test
    fun `the device runs are known to the device projections and absent from the host comparison`() {
        // Which runs are device runs is a registry fact; naming them here would be the second list
        // this whole version exists to remove.
        val runs = registryRuns()
        fun ofKind(kind: String) = runs.filterValues { str(it["run_kind"]) == kind }
            .entries.sortedBy { int(it.value["ordinal"]) }.map { it.key }

        val official = ofKind("device_official")
        val smoke = ofKind("device_smoke")
        val device = (official + smoke).sortedBy { runs[it]?.let { r -> int(r["ordinal"]) } ?: 0 }
        assertTrue("the registry must declare at least one device run", device.isNotEmpty())

        val host = V10RunIdentity.Projections.hostComparisonRuns
        device.forEach { key ->
            assertTrue("$key must not be in the host comparison", !host.contains(key))
        }
        assertEquals(official, V10RunIdentity.Projections.deviceOfficialRuns)
        assertEquals(smoke, V10RunIdentity.Projections.deviceSmokeRuns)
        assertEquals(device, V10RunIdentity.Projections.phaseBRuns)
        assertTrue("every device run must be in identity parity",
                   V10RunIdentity.Projections.identityParityRuns.containsAll(device))
    }
}
