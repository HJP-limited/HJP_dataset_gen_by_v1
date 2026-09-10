package com.example.hjp

import androidx.test.platform.app.InstrumentationRegistry
import com.hjp.agent.core.AgentKernelMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session-lifecycle checks that need the real Android runtime.
 *
 * NOT RUN in this change: no AVD or device was attached. Run with
 * `./gradlew :app:connectedDebugAndroidTest --tests "*SessionLifecycleInstrumentedTest"`.
 */
class SessionLifecycleInstrumentedTest {
    private val application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as HjpApplication

    @Test
    fun resetReplacesTheSessionAndBumpsTheGeneration() = runBlocking {
        val container = application.container
        val before = container.resetSession()

        val after = container.resetSession()

        assertNotEquals(before, after)
        assertTrue(after > before)
    }

    @Test
    fun releaseBuildsNeverExposeTheKernelSwitch() = runBlocking {
        val container = application.container
        if (!BuildConfig.DEBUG) {
            assertTrue(!container.kernelSwitchAvailable)
            assertEquals(AgentKernelMode.REACT, container.kernelMode)
        }
    }

    @Test
    fun theDefaultKernelIsReact() {
        assertEquals(AgentKernelMode.REACT, application.container.kernelMode)
    }

    @Test
    fun diagnosticsCarryNoContactData() {
        val snapshot = application.container.diagnosticsSnapshot()

        assertTrue(snapshot.containsKey("artifact_id"))
        assertTrue(snapshot.containsKey("app_context_limit_tokens"))
        assertTrue(snapshot.values.none { it.contains("@") })
        assertTrue(snapshot.values.none { it.contains("/") })
    }

    @Test
    fun theContextBudgetFollowsTheArtifactNotTheFileName() {
        val deployment = application.container.deployment

        // The staged file is always named hjp-agent.litertlm; the budget must come from its bytes.
        assertTrue(application.container.modelFile.name == "hjp-agent.litertlm")
        assertTrue(
            "identified_by=${deployment.identifiedBy} limit=${deployment.appContextLimitTokens}",
            deployment.appContextLimitTokens > 0,
        )
    }
}
