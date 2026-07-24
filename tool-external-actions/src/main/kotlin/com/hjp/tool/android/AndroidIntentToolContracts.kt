package com.hjp.tool.android

import com.hjp.tool.contract.ConfirmationPolicy
import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.PiiLevel
import com.hjp.tool.contract.ToolCapabilityId
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolEffect
import com.hjp.tool.contract.ToolPresentation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private fun string(description: String, enumValues: List<String>? = null) = buildJsonObject {
    put("type", "string"); put("description", description)
    enumValues?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
}

private fun schema(required: List<String>, properties: kotlinx.serialization.json.JsonObject) = buildJsonObject {
    put("type", "object"); put("additionalProperties", false); put("properties", properties)
    put("required", JsonArray(required.map(::JsonPrimitive)))
}

internal val CALENDAR_INPUT_SCHEMA = schema(listOf("title", "start_time"), buildJsonObject {
    put("title", string("일정 제목"))
    put("start_time", string("기기 현지 시간대의 yyyy-MM-dd'T'HH:mm 시작 시각"))
    put("end_time", string("기기 현지 시간대의 yyyy-MM-dd'T'HH:mm 종료 시각. 생략 시 1시간 후"))
    put("location", string("장소")); put("description", string("일정 메모"))
    putJsonObject("attendee_emails") { put("type", "array"); putJsonObject("items") { put("type", "string"); put("format", "email") } }
})

internal val COMPOSE_INPUT_SCHEMA = schema(listOf("channel", "to"), buildJsonObject {
    put("channel", string("작성할 메시지 종류", listOf("email", "sms")))
    put("to", string("email이면 실제 이메일 주소, sms이면 실제 전화번호. 이름이나 명함 ID를 넣지 말 것"))
    put("subject", string("이메일 제목. sms에서는 사용하지 않음"))
    put("body", string("사용자가 확인할 초안 본문. 생략하면 빈 본문으로 작성 화면을 엽니다."))
})

internal val EXTERNAL_UI_OUTPUT_SCHEMA = schema(listOf("opened", "destination", "requires_user_confirmation"), buildJsonObject {
    putJsonObject("opened") { put("type", "boolean") }
    put("destination", string("열린 외부 작성 화면"))
    putJsonObject("requires_user_confirmation") { put("type", "boolean") }
    put("execution_mode", string("실행 방식", listOf("mock")))
})

object AndroidIntentToolContracts {
    val Calendar = ToolContract(
        ToolCapabilityId("calendar.open_insert"), "create_calendar_event", ContractVersion(1, 0),
        "캘린더 앱의 일정 작성 화면을 열고 내용을 미리 채웁니다. 저장은 사용자가 합니다.",
        CALENDAR_INPUT_SCHEMA, EXTERNAL_UI_OUTPUT_SCHEMA, ToolEffect.EXTERNAL_UI,
        ConfirmationPolicy.EXTERNAL_APP_CONFIRMATION, PiiLevel.SENSITIVE_CONTACT, PiiLevel.NONE,
        defaultTimeoutMillis = 3_000,
        presentation = ToolPresentation("캘린더 작성 화면을 열고 있어요.", "캘린더 작성 화면을 열었어요. 저장 전에 확인해 주세요.", "캘린더 앱을 사용할 수 없어요."),
    )

    val Compose = ToolContract(
        ToolCapabilityId("message.open_compose"), "open_compose", ContractVersion(1, 0),
        "이메일 또는 문자 작성 화면을 열고 초안을 미리 채웁니다. 전송은 사용자가 합니다.",
        COMPOSE_INPUT_SCHEMA, EXTERNAL_UI_OUTPUT_SCHEMA, ToolEffect.EXTERNAL_UI,
        ConfirmationPolicy.EXTERNAL_APP_CONFIRMATION, PiiLevel.SENSITIVE_CONTACT, PiiLevel.NONE,
        defaultTimeoutMillis = 3_000,
        presentation = ToolPresentation("메시지 작성 화면을 열고 있어요.", "작성 화면을 열었어요. 전송 전에 확인해 주세요.", "메일 또는 문자 앱을 사용할 수 없어요."),
    )
}
