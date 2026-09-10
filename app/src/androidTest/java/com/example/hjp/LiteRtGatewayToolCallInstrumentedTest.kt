package com.example.hjp

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.core.ArtifactIdentification
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One real production turn: native generation, LiteRt gateway decision, and one read-only tool. */
@RunWith(AndroidJUnit4::class)
class LiteRtGatewayToolCallInstrumentedTest {
    @Test
    fun actualGatewayGeneratesAndCallsCurrentDateTimeTool() {
        runBlocking {
            assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
            assumeFalse(AppContainer.isAndroidEmulator())

            val application = InstrumentationRegistry.getInstrumentation().targetContext
                .applicationContext as HjpApplication
            val container = application.container
            assertEquals(ArtifactIdentification.KNOWN_ARTIFACT_VERIFIED, container.deployment.identifiedBy)
            assertTrue(container.modelReady)

            val before = container.runtimeCounters.snapshot()
            val events = mutableListOf<AgentEvent>()
            val startedAt = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "TURN_START pid=${Process.myPid()} artifact=${container.deployment.artifactId} " +
                    "sha256=${container.deployment.verifiedSha256} gateway=LiteRtAgentModelGateway " +
                    "kernel=${container.kernelMode}",
            )

            try {
                withTimeout(TURN_TIMEOUT_MILLIS) {
                    container.engine.runTurn("현재 날짜와 시간을 도구로 확인해서 알려줘.")
                        .collect { event ->
                            events += event
                            when (event) {
                                is AgentEvent.TurnStarted -> Log.i(TAG, "EVENT turn_started")
                                is AgentEvent.ToolStarted -> Log.i(TAG, "EVENT tool_started=${event.messageKo}")
                                is AgentEvent.ToolFinished -> Log.i(TAG, "EVENT tool_finished=${event.messageKo}")
                                is AgentEvent.ConfirmationRequested -> Log.i(TAG, "EVENT unexpected_confirmation")
                                is AgentEvent.PermissionRequested -> Log.i(TAG, "EVENT unexpected_permission")
                                is AgentEvent.Token -> Unit
                                is AgentEvent.FinalMessage -> Log.i(
                                    TAG,
                                    "EVENT final_message chars=${event.text.length}",
                                )
                                is AgentEvent.UserError -> Log.i(TAG, "EVENT user_error=${event.code}")
                            }
                        }
                }

                val delta = container.runtimeCounters.snapshot() - before
                val session = container.sessionSnapshot()
                val action = session.conversationMemory.actions.lastOrNull()
                val finalText = events.filterIsInstance<AgentEvent.FinalMessage>().lastOrNull()?.text.orEmpty()
                val streamedText = events.filterIsInstance<AgentEvent.Token>().joinToString("") { it.text }

                assertTrue("model load did not succeed: $delta", delta.modelLoadSuccesses >= 1)
                assertTrue("model session did not open: $delta", delta.modelSessionOpenSuccesses >= 1)
                assertTrue("actual gateway produced no decision: $delta", delta.modelInvocationSuccesses >= 1)
                assertEquals("model invocation failed: $delta", 0, delta.modelInvocationFailures)
                assertEquals("model invocation timed out: $delta", 0, delta.modelInvocationTimeouts)
                assertTrue("actual model counter is false: $delta", delta.actualModelExecuted)
                assertTrue(
                    "datetime tool never started: $events",
                    events.filterIsInstance<AgentEvent.ToolStarted>()
                        .any { it.messageKo == DATETIME_TOOL_STARTED },
                )
                assertTrue(
                    "datetime tool never finished: $events",
                    events.filterIsInstance<AgentEvent.ToolFinished>()
                        .any { it.messageKo == DATETIME_TOOL_FINISHED },
                )
                assertTrue(
                    "session did not record the exact tool: $action",
                    action?.executedTools?.contains(DATETIME_TOOL_NAME) == true,
                )
                assertTrue("model produced no final response", finalText.isNotBlank() || streamedText.isNotBlank())
                assertEquals("semantic retrieval unexpectedly ran", 0, delta.semanticInvocations)

                Log.i(
                    TAG,
                    "GATEWAY_INFERENCE_VERIFIED pid=${Process.myPid()} elapsed_ms=" +
                        "${SystemClock.elapsedRealtime() - startedAt} invocations=" +
                        "${delta.modelInvocationSuccesses} model_latency_ms=${delta.modelLatencyTotalMillis}",
                )
                Log.i(TAG, "TOOL_CALL_VERIFIED name=$DATETIME_TOOL_NAME status=success")
                Log.i(
                    TAG,
                    "TURN_SUCCESS final_chars=${maxOf(finalText.length, streamedText.length)} " +
                        "semantic_invocations=${delta.semanticInvocations}",
                )
            } finally {
                container.close()
                Log.i(TAG, "CONTAINER_CLOSED pid=${Process.myPid()}")
            }
        }
    }

    private companion object {
        const val TAG = "HjpLiteRtGatewayVerify"
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val DATETIME_TOOL_NAME = "get_current_datetime"
        const val DATETIME_TOOL_STARTED = "현재 날짜와 시각을 확인하고 있어요."
        const val DATETIME_TOOL_FINISHED = "현재 날짜와 시각을 확인했어요."
    }
}
