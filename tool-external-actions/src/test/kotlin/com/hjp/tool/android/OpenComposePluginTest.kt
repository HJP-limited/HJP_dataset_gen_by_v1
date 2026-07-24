package com.hjp.tool.android

import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenComposePluginTest {
    @Test
    fun `email channel rejects contact id instead of opening composer`() = runBlocking {
        val backend = RecordingBackend()
        val result = plugin(backend).execute(request("email", "C001"), context())

        assertTrue(result is ToolExecutionResult.Failure)
        assertEquals("tool.invalid_arguments", (result as ToolExecutionResult.Failure).error.code.value)
        assertFalse(backend.opened)
    }

    @Test
    fun `sms channel rejects name and accepts formatted phone number`() = runBlocking {
        val backend = RecordingBackend()
        val invalid = plugin(backend).execute(request("sms", "김지원"), context())
        val valid = plugin(backend).execute(request("sms", "010-1234-5678"), context())

        assertTrue(invalid is ToolExecutionResult.Failure)
        assertTrue(valid is ToolExecutionResult.Success)
        assertEquals("010-1234-5678", backend.lastDraft?.to)
    }

    @Test
    fun `email channel accepts ordinary address`() = runBlocking {
        val backend = RecordingBackend()
        val result = plugin(backend).execute(request("email", "test@example.com"), context())

        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("test@example.com", backend.lastDraft?.to)
    }

    private fun plugin(backend: RecordingBackend) = OpenComposePlugin(backend)

    private fun request(channel: String, to: String) = ToolRequest(
        callId = "call-1",
        capabilityId = AndroidIntentToolContracts.Compose.capabilityId,
        contractVersion = AndroidIntentToolContracts.Compose.version,
        arguments = buildJsonObject {
            put("channel", channel)
            put("to", to)
            put("body", "안녕하세요")
        },
    )

    private fun context() = ToolExecutionContext(
        sessionId = "session",
        turnId = "turn",
        localeTag = "ko-KR",
        deviceTimeZoneId = "Asia/Seoul",
    )

    private class RecordingBackend : MessageComposerBackend {
        var opened = false
        var lastDraft: MessageDraft? = null

        override fun isAvailable(channel: MessageChannel?) = true

        override suspend fun open(draft: MessageDraft): Boolean {
            opened = true
            lastDraft = draft
            return true
        }
    }
}
