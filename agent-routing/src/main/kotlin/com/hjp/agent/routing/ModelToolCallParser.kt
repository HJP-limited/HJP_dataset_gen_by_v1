package com.hjp.agent.routing

import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelToolCall
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Converts subprocess model JSON into the same ModelToolCall used by Android LiteRT-LM. */
object ModelToolCallParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseDecision(rawOutput: String, allowPlainTextFinal: Boolean = true): ModelDecision {
        val cleaned = clean(rawOutput)
        val candidate = extractJsonObject(cleaned)
        if (candidate != null) {
            val root = runCatching { json.parseToJsonElement(candidate) as? JsonObject }.getOrNull()
            if (root != null) parseObject(root)?.let { return it }
        }
        return if (allowPlainTextFinal && cleaned.isNotBlank()) {
            ModelDecision.FinalCandidate(cleaned)
        } else {
            ModelDecision.Invalid("모델 출력이 tool call 형식이 아닙니다.", retryable = true)
        }
    }

    private fun parseObject(root: JsonObject): ModelDecision? {
        val type = root.string("type")?.lowercase()
        if (type == "final" || type == "final_answer") {
            return root.string("text")?.let(ModelDecision::FinalCandidate)
        }

        val directName = root.string("name") ?: root.string("tool") ?: root.string("model_tool_name")
        if (type == "tool_call" || directName != null) {
            val name = directName ?: return ModelDecision.Invalid("tool call에 name이 없습니다.", false)
            val arguments = root.objectValue("arguments") ?: root.objectValue("args")
                ?: return ModelDecision.Invalid("tool call의 arguments는 JSON object여야 합니다.", false)
            return ModelDecision.ToolCalls(listOf(ModelToolCall(
                root.string("call_id") ?: UUID.randomUUID().toString(), name, arguments,
            )))
        }

        val calls = root["tool_calls"] as? JsonArray ?: return root.string("text")?.let(ModelDecision::FinalCandidate)
        val parsed = calls.mapNotNull { element ->
            val call = element as? JsonObject ?: return@mapNotNull null
            val function = call["function"] as? JsonObject ?: call
            val name = function.string("name") ?: return@mapNotNull null
            val argsElement = function["arguments"]
            val arguments = when (argsElement) {
                is JsonObject -> argsElement
                is JsonPrimitive -> runCatching { json.parseToJsonElement(argsElement.content) as? JsonObject }.getOrNull()
                else -> null
            } ?: return@mapNotNull null
            ModelToolCall(call.string("id") ?: UUID.randomUUID().toString(), name, arguments)
        }
        return if (parsed.isNotEmpty()) ModelDecision.ToolCalls(parsed)
        else ModelDecision.Invalid("tool_calls 형식이 올바르지 않습니다.", false)
    }

    private fun clean(raw: String): String = raw
        .replace(ANSI_ESCAPE, "")
        .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*```$"), "")
        .trim()

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth += 1
                    '}' -> {
                        depth -= 1
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)

    private fun JsonObject.objectValue(key: String): JsonObject? = get(key) as? JsonObject

    private val ANSI_ESCAPE = Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]")
}
