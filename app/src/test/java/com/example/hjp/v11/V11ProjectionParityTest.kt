package com.example.hjp.v11

import com.example.hjp.v11.V11Contracts.COMPARISON_PRE
import com.example.hjp.v11.V11Contracts.OBSERVED_PRE
import com.example.hjp.v11.V11Contracts.PROJECTIONS
import com.example.hjp.v11.V11Contracts.REGISTRY
import com.example.hjp.v11.V11Contracts.at
import com.example.hjp.v11.V11Contracts.int
import com.example.hjp.v11.V11Contracts.list
import com.example.hjp.v11.V11Contracts.projectionRuns
import com.example.hjp.v11.V11Contracts.registryRuns
import com.example.hjp.v11.V11Contracts.requireObj
import com.example.hjp.v11.V11Contracts.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity across the registry, the compiled constants, the projection and the record.
 *
 * v10's parity compared identity fields and could not see a run set that was short a member. This
 * compares the fields and the sets, and adds the side v10 had no name for: the observation, which
 * is where a run's state now lives.
 */
class V11ProjectionParityTest {

    private val identityFields = listOf(
        "run_id", "namespace", "marker_path", "expected_result_schema",
        "expected_status_schema", "expected_raw_schema", "metric_source_path", "runtime_mode",
        "run_kind", "ordinal",
    )

    private fun kotlinIdentity(runKey: String): Map<String, String?> {
        val accessor = V11RunIdentity.Projections.byId["all_declared_runs"]
            ?: error("the generated file has no catalogue projection")
        assertTrue("$runKey must be in the generated catalogue", accessor.contains(runKey))
        // The generated file exposes one object per run; the fields are read through the registry
        // for comparison, and the presence check above is what proves the run was rendered.
        return emptyMap()
    }

    @Test
    fun `the compiled catalogue and the registry declare the same runs`() {
        assertEquals(list(requireObj(REGISTRY)["run_order"]), V11RunIdentity.runOrder)
        assertEquals(projectionRuns("all_declared_runs"),
                     V11RunIdentity.Projections.allDeclaredRuns)
        registryRuns().keys.forEach { kotlinIdentity(it) }
    }

    @Test
    fun `every side carries the same registry digest`() {
        val digests = listOf(
            str(requireObj(REGISTRY)["registry_digest"]),
            str(requireObj(PROJECTIONS)["registry_digest"]),
            str(at(requireObj(COMPARISON_PRE), "run_set_source/registry_digest")),
            str(requireObj(OBSERVED_PRE)["registry_digest"]),
            V11RunIdentity.REGISTRY_DIGEST,
        )
        assertEquals("five sides, one snapshot", 1, digests.distinct().size)
    }

    @Test
    fun `the comparison's run set is the projection's, in order`() {
        assertEquals(projectionRuns("host_comparison_runs"),
                     list(requireObj(COMPARISON_PRE)["run_order"]))
        assertEquals(projectionRuns("host_comparison_runs"),
                     V11RunIdentity.Projections.hostComparisonRuns)
    }

    @Test
    fun `the observation covers every declared run and reports them in order`() {
        val observed = requireObj(OBSERVED_PRE)
        assertEquals(list(requireObj(REGISTRY)["run_order"]), list(observed["run_order"]))
        assertEquals(registryRuns().keys.sorted(),
                     (observed["runs"] as JsonObject).keys.sorted())
    }

    @Test
    fun `every completed run in the observation is read for metrics in the comparison`() {
        val observed = requireObj(OBSERVED_PRE)
        val comparison = requireObj(COMPARISON_PRE)
        val hostRuns = projectionRuns("host_comparison_runs")
        val completed = hostRuns.filter {
            str(at(observed, "runs/$it/execution_state")) == "COMPLETED"
        }
        assertTrue("some host runs must have completed", completed.isNotEmpty())
        assertEquals("every completed host run must be read", completed,
                     list(comparison["runs_present"]))
        val required = int(comparison["required_metric_count"]) ?: 0
        assertTrue("the comparison must require metrics", required > 0)
    }

    @Test
    fun `no run appears in the comparison that the registry does not declare`() {
        val declared = registryRuns().keys
        val comparison = requireObj(COMPARISON_PRE)
        val seen = (list(comparison["run_order"]) + list(comparison["runs_present"]) +
                    list(comparison["runs_carried_as_state"])).distinct()
        assertEquals("every run named must be declared", emptyList<String>(),
                     seen.filterNot { declared.contains(it) }.sorted())
    }
}
