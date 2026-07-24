package com.hjp.agent.routing

import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelToolResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Safe fallback for model-originated calls: it only renders values present in tool output. */
object GroundedToolResultFormatter {
    fun finish(result: ModelToolResponse): ModelDecision.FinalCandidate {
        if (!result.ok()) return ModelDecision.FinalCandidate(result.errorMessage())
        val data = result.payload["data"] as? JsonObject ?: JsonObject(emptyMap())
        val text = when (result.modelToolName) {
            "search_contacts" -> formatSearch(data)
            "get_contact" -> formatContact(data)
            "get_current_datetime" -> "현재 날짜와 시각: ${data.string("datetime").orEmpty()} (${data.string("timezone").orEmpty()})"
            "create_calendar_event" -> "캘린더 작성 내용을 준비했습니다. Desktop에서는 Android 캘린더 Intent를 실행하지 않았습니다."
            "open_compose" -> "메시지 작성 내용을 준비했습니다. Desktop에서는 실제 메일 또는 문자를 전송하지 않았습니다."
            "update_business_card" -> "명함을 수정했습니다."
            else -> "도구 실행을 완료했습니다."
        }
        return ModelDecision.FinalCandidate(text)
    }

    private fun formatSearch(data: JsonObject): String {
        val items = (data["results"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (items.isEmpty()) return "해당하는 명함을 찾지 못했습니다."
        return "명함 검색 결과입니다.\n" + items.joinToString("\n") { item ->
            listOf(item.string("name"), item.string("company"), item.string("title"))
                .filterNotNull().filter(String::isNotBlank).joinToString(" · ", prefix = "- ")
        }
    }

    private fun formatContact(data: JsonObject): String {
        val fields = listOf("name", "company", "title", "phone", "mobile", "email", "address")
            .mapNotNull { key -> data.string(key)?.let { key to it } }
        return if (fields.isEmpty()) "해당 명함을 찾지 못했습니다."
        else "명함 상세정보입니다.\n" + fields.joinToString("\n") { (key, value) -> "- $key: $value" }
    }

    private fun ModelToolResponse.ok(): Boolean =
        (payload["ok"] as? JsonPrimitive)?.booleanOrNull == true

    private fun ModelToolResponse.errorMessage(): String =
        (((payload["error"] as? JsonObject)?.get("message_ko")) as? JsonPrimitive)?.content
            ?.takeIf(String::isNotBlank) ?: "도구 실행을 완료하지 못했습니다."

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)
}
