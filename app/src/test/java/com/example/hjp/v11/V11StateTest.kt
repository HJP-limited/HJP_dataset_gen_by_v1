package com.example.hjp.v11

import com.example.hjp.v11.V11Contracts.COMPARISON_PRE
import com.example.hjp.v11.V11Contracts.HOLD_COVERAGE
import com.example.hjp.v11.V11Contracts.OBSERVED_PRE
import com.example.hjp.v11.V11Contracts.RESOLVER_SELFTEST
import com.example.hjp.v11.V11Contracts.STATE_CONTRACT
import com.example.hjp.v11.V11Contracts.V10_COMPARISON
import com.example.hjp.v11.V11Contracts.at
import com.example.hjp.v11.V11Contracts.bool
import com.example.hjp.v11.V11Contracts.int
import com.example.hjp.v11.V11Contracts.list
import com.example.hjp.v11.V11Contracts.observedState
import com.example.hjp.v11.V11Contracts.projectionRuns
import com.example.hjp.v11.V11Contracts.registryRuns
import com.example.hjp.v11.V11Contracts.requireObj
import com.example.hjp.v11.V11Contracts.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A run's state is what the tree says now, not what a contract said once.
 *
 * The case that matters is K10: v10's comparison carries it as NOT_YET_EXECUTED and v11's
 * observation calls it COMPLETED, from the same files. Nothing about K10 changed between the two
 * readings — what changed is who was asked.
 */
class V11StateTest {

    @Test
    fun `K10 reads COMPLETED where v10 carried it as not yet executed`() {
        assertEquals("COMPLETED", observedState("K10"))
        val v10 = requireObj(V10_COMPARISON)
        assertEquals("NOT_YET_EXECUTED", str(at(v10, "runs_carried_as_state/K10/run_state")))
        val v10Metrics = (at(v10, "run_metric_reads/K10") as? JsonObject)?.size ?: 0
        assertEquals("v10 carried none of K10's metrics", 0, v10Metrics)
        val v11Metrics = (at(requireObj(COMPARISON_PRE), "run_metric_reads/K10") as? JsonObject)
            ?.size ?: 0
        assertEquals("v11 carries all of them",
                     int(requireObj(COMPARISON_PRE)["required_metric_count"]), v11Metrics)
    }

    @Test
    fun `execution state and validity are two fields`() {
        // K4 completed and its own gate called it INVALID RUN. One field could not say both.
        assertEquals("COMPLETED", observedState("K4"))
        assertEquals("INVALID RUN", str(at(requireObj(OBSERVED_PRE), "runs/K4/validity")))
        assertEquals("COMPLETED", observedState("K5"))
        assertEquals("VALID BASELINE", str(at(requireObj(OBSERVED_PRE), "runs/K5/validity")))
        assertEquals(true, bool(at(requireObj(OBSERVED_PRE),
                                   "runs/K4/execution_state_and_validity_are_two_fields")))
    }

    @Test
    fun `a run with no authority reads NOT_YET_EXECUTED`() {
        projectionRuns("current_version_runs").forEach { key ->
            assertEquals("$key has not run", "NOT_YET_EXECUTED", observedState(key))
            assertEquals("$key must have no authority present", 0,
                         int(at(requireObj(OBSERVED_PRE), "runs/$key/evidence/present_count")))
        }
    }

    @Test
    fun `a recorded hold is read from the record, never inferred from absence`() {
        val contract = requireObj(STATE_CONTRACT)
        val holds = at(contract, "historical_holds") as JsonObject
        assertTrue("there must be recorded holds", holds.isNotEmpty())
        holds.keys.forEach { key ->
            assertEquals("$key must read as held", "NOT_RUN_DUE_TO_PREFLIGHT_HOLD",
                         observedState(key))
            val authority = str(at(requireObj(OBSERVED_PRE), "runs/$key/state_authority_path"))
            assertNotNull("$key's state must name the record it came from", authority)
            assertEquals("and it must be the one the contract declares",
                         str(at(contract, "historical_holds/$key/authority_path")), authority)
        }
        assertNotNull("the contract must say why absence is not a hold",
                      str(at(contract,
                             "states/NOT_RUN_DUE_TO_PREFLIGHT_HOLD/may_not_be_inferred_from")))
    }

    @Test
    fun `every run with a declared hold authority has a readable one`() {
        val coverage = requireObj(HOLD_COVERAGE)
        assertEquals("HISTORICAL HOLDS COVERED", str(coverage["verdict"]))
        assertEquals(0, int(coverage["failure_count"]))
        assertEquals(projectionRuns("runs_with_historical_state_authority"),
                     list(coverage["runs"]))
    }

    @Test
    fun `the resolver returns each state the model names`() {
        val selftest = requireObj(RESOLVER_SELFTEST)
        assertEquals("STATE RESOLVER SELFTEST PASS", str(selftest["verdict"]))
        assertEquals(0, int(selftest["disagreement_count"]))
        assertEquals(true, bool(selftest["same_resolver_digest_for_every_scenario"]))
        val distinct = list(selftest["distinct_states_observed"])
        assertTrue("the resolver must be able to return more than one state", distinct.size > 1)
        assertTrue("it must be able to say a run completed", distinct.contains("COMPLETED"))
        assertTrue("and that one has not started", distinct.contains("NOT_YET_EXECUTED"))
    }

    @Test
    fun `the observation says plainly that it stores nothing`() {
        val observed = requireObj(OBSERVED_PRE)
        assertNotNull(str(observed["no_state_is_stored_anywhere"]))
        assertEquals(str(at(requireObj(RESOLVER_SELFTEST), "resolver_sha256")),
                     str(observed["resolver_sha256"]))
        assertEquals("every declared run must be observed",
                     registryRuns().keys.sorted(),
                     (observed["runs"] as JsonObject).keys.sorted())
    }
}
