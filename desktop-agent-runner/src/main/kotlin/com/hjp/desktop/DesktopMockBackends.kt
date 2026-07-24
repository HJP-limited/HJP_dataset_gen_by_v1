package com.hjp.desktop

import com.hjp.tool.android.CalendarComposerBackend
import com.hjp.tool.android.CalendarDraft
import com.hjp.tool.android.MessageChannel
import com.hjp.tool.android.MessageComposerBackend
import com.hjp.tool.android.MessageDraft
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DesktopMockActionRecorder {
    @Volatile private var latest: JsonObject? = null

    fun record(value: JsonObject) {
        latest = value
    }

    fun consume(): JsonObject? = synchronized(this) {
        latest.also { latest = null }
    }
}

class DesktopCalendarComposerBackend(
    private val recorder: DesktopMockActionRecorder,
    private val timeZoneId: String,
) : CalendarComposerBackend {
    override val isMock: Boolean = true
    override fun isAvailable(): Boolean = true

    override suspend fun open(draft: CalendarDraft): Boolean {
        val zone = ZoneId.of(timeZoneId)
        recorder.record(buildJsonObject {
            put("status", "mock_success")
            put("tool", "create_calendar_event")
            put("title", draft.title)
            put("start_time", Instant.ofEpochMilli(draft.startMillis).atZone(zone).toLocalDateTime().toString())
            put("end_time", Instant.ofEpochMilli(draft.endMillis).atZone(zone).toLocalDateTime().toString())
            draft.location?.let { put("location", it) }
            draft.description?.let { put("description", it) }
            put("attendee_emails", draft.attendeeEmails.joinToString(","))
            put("message", "Desktop 테스트에서는 Android 캘린더 Intent를 실행하지 않았습니다.")
        })
        return true
    }
}

class DesktopMessageComposerBackend(
    private val recorder: DesktopMockActionRecorder,
) : MessageComposerBackend {
    override val isMock: Boolean = true
    override fun isAvailable(channel: MessageChannel?): Boolean = true

    override suspend fun open(draft: MessageDraft): Boolean {
        recorder.record(buildJsonObject {
            put("status", "mock_success")
            put("tool", "open_compose")
            put("channel", draft.channel.name.lowercase())
            put("to", draft.to)
            draft.subject?.let { put("subject", it) }
            put("body", draft.body)
            put("message", "Desktop 테스트에서는 Android 작성 Activity를 열거나 메시지를 전송하지 않았습니다.")
        })
        return true
    }
}
