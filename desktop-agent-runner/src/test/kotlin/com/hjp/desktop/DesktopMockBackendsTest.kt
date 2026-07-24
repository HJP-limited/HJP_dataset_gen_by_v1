package com.hjp.desktop

import com.hjp.tool.android.CalendarDraft
import com.hjp.tool.android.MessageChannel
import com.hjp.tool.android.MessageDraft
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopMockBackendsTest {
    @Test
    fun `calendar mock returns structured safe record`() = runBlocking {
        val recorder = DesktopMockActionRecorder()
        val backend = DesktopCalendarComposerBackend(recorder, "Asia/Seoul")

        assertTrue(backend.open(CalendarDraft("팀 회의", 0, 3_600_000, "서울", "논의", listOf("a@example.com"))))
        val result = requireNotNull(recorder.consume())

        assertEquals("mock_success", (result["status"] as JsonPrimitive).content)
        assertEquals("create_calendar_event", (result["tool"] as JsonPrimitive).content)
        assertTrue((result["message"] as JsonPrimitive).content.contains("Intent"))
    }

    @Test
    fun `message mock records recipient subject and body without sending`() = runBlocking {
        val recorder = DesktopMockActionRecorder()
        val backend = DesktopMessageComposerBackend(recorder)

        assertTrue(backend.open(MessageDraft(MessageChannel.EMAIL, "to@example.com", "제목", "본문")))
        val result = requireNotNull(recorder.consume())

        assertEquals("to@example.com", (result["to"] as JsonPrimitive).content)
        assertEquals("제목", (result["subject"] as JsonPrimitive).content)
        assertEquals("본문", (result["body"] as JsonPrimitive).content)
    }
}
