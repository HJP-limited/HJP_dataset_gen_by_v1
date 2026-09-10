package com.example.hjp.v11

import com.example.hjp.deviceeval.DeviceRunEvidenceV11
import com.example.hjp.v11.V11Contracts.projectionRuns
import com.example.hjp.v11.V11Contracts.registryRuns
import com.example.hjp.v11.V11Contracts.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device guard's list of foreign namespaces must not fall behind the registry.
 *
 * It has fallen behind twice. v5's list stopped at v4; v10's stopped at v8, which left
 * `ryeong_device_eval_v9_official` and both v10 directories writable by a v10 run. Nothing went
 * wrong because no device run has ever been invoked — every one from D7 onward was held — but the
 * guard was not guarding, and it looked exactly like a guard that was.
 */
class V11DeviceIdentityTest {

    @Test
    fun `every historical device namespace the registry declares is foreign to this run`() {
        val historical = projectionRuns("historical_device_runs")
        assertTrue("earlier versions declared device namespaces", historical.isNotEmpty())
        val declared = historical.mapNotNull { str(registryRuns()[it]?.get("namespace")) }.toSet()
        val missing = (declared - DeviceRunEvidenceV11.FOREIGN_DIRECTORIES).sorted()
        assertEquals("a namespace the registry calls historical must be foreign to a v11 run",
                     emptyList<String>(), missing)
    }

    @Test
    fun `this version's own namespaces are not foreign to it`() {
        val current = projectionRuns("current_version_runs")
            .mapNotNull { str(registryRuns()[it]?.get("namespace")) }
            .filter { it.startsWith("ryeong_device_eval") }
        assertTrue("this version must own device namespaces", current.isNotEmpty())
        val blocked = current.filter { DeviceRunEvidenceV11.FOREIGN_DIRECTORIES.contains(it) }
        assertEquals("a run may not be refused its own namespace", emptyList<String>(), blocked)
    }

    @Test
    fun `the guard matches whole names rather than substrings`() {
        // ryeong_device_eval_v1 would be a substring of ryeong_device_eval_v11_official. The guard
        // uses set membership on the whole name, which is why that is not a problem here — and the
        // host runner had exactly this bug in v10, so it is worth asserting rather than assuming.
        val ours = "ryeong_device_eval_v11_official"
        assertTrue("the guard must not hold a prefix of this run's own namespace",
                   DeviceRunEvidenceV11.FOREIGN_DIRECTORIES.none { ours.startsWith(it) })
    }
}
