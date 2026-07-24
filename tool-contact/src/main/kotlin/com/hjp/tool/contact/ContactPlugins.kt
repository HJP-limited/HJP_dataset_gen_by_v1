package com.hjp.tool.contact

import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.DecodeResult
import com.hjp.tool.contract.SessionStateKey
import com.hjp.tool.contract.SessionStateUpdate
import com.hjp.tool.contract.ToolAvailability
import com.hjp.tool.contract.ToolError
import com.hjp.tool.contract.ToolErrorCode
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolImplementationId
import com.hjp.tool.contract.ToolInputCodec
import com.hjp.tool.contract.ToolOutputCodec
import com.hjp.tool.contract.ToolRequest
import com.hjp.tool.contract.TypedToolPlugin
import com.hjp.tool.contract.TypedToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class SearchContactsInput(val query: String, val limit: Int)
data class SearchContactItem(val cardId: String, val name: String, val company: String, val title: String, val location: String, val score: Double)
data class SearchContactsOutput(val results: List<SearchContactItem>, val engine: String)
data class GetContactInput(val cardId: String, val purpose: String)
data class GetContactOutput(val card: BusinessCardRecord)
data class UpdateBusinessCardInput(val cardId: String, val updates: Map<String, String>, val clearFields: Set<String>)
data class UpdateBusinessCardOutput(val before: BusinessCardRecord, val after: BusinessCardRecord)

private object SearchInputCodec : ToolInputCodec<SearchContactsInput> {
    override val schema = SEARCH_INPUT_SCHEMA
    override fun decode(arguments: JsonObject): DecodeResult<SearchContactsInput> {
        val queryPrimitive = arguments["query"] as? JsonPrimitive
        if (queryPrimitive == null || !queryPrimitive.isString) {
            return DecodeResult.Failure("검색어는 문자열이어야 합니다.", "query")
        }
        val query = queryPrimitive.content.trim()
        val limitPrimitive = arguments["limit"] as? JsonPrimitive
        if (limitPrimitive != null && limitPrimitive.isString) {
            return DecodeResult.Failure("검색 개수는 정수여야 합니다.", "limit")
        }
        val limit = limitPrimitive?.intOrNull ?: 5
        if (query.isBlank()) return DecodeResult.Failure("검색어를 입력해 주세요.", "query")
        if (limit !in 1..10) return DecodeResult.Failure("검색 개수는 1에서 10 사이여야 합니다.", "limit")
        return DecodeResult.Success(SearchContactsInput(query, limit))
    }
}

private object SearchOutputCodec : ToolOutputCodec<SearchContactsOutput> {
    override val schema = SEARCH_OUTPUT_SCHEMA
    override fun encode(value: SearchContactsOutput) = buildJsonObject {
        put("results", buildJsonArray {
            value.results.forEach { item -> add(buildJsonObject {
                put("card_id", item.cardId); put("name", item.name); put("company", item.company)
                put("title", item.title); put("location", item.location); put("score", item.score)
            }) }
        })
        put("count", value.results.size)
        put("engine", value.engine)
    }
}

private object GetInputCodec : ToolInputCodec<GetContactInput> {
    override val schema = GET_INPUT_SCHEMA
    override fun decode(arguments: JsonObject): DecodeResult<GetContactInput> {
        val cardPrimitive = arguments["card_id"] as? JsonPrimitive
        if (cardPrimitive == null || !cardPrimitive.isString) return DecodeResult.Failure("명함 ID는 문자열이어야 합니다.", "card_id")
        val cardId = cardPrimitive.content.trim()
        val purposePrimitive = arguments["purpose"] as? JsonPrimitive
        if (purposePrimitive != null && !purposePrimitive.isString) return DecodeResult.Failure("조회 목적은 문자열이어야 합니다.", "purpose")
        val purpose = purposePrimitive?.content?.trim()?.lowercase() ?: "display"
        if (cardId.isBlank()) return DecodeResult.Failure("명함 ID가 필요합니다.", "card_id")
        if (purpose !in setOf("display", "email", "sms", "calendar"))
            return DecodeResult.Failure("올바른 조회 목적이 필요합니다.", "purpose")
        return DecodeResult.Success(GetContactInput(cardId, purpose))
    }
}

private object GetOutputCodec : ToolOutputCodec<GetContactOutput> {
    override val schema = GET_OUTPUT_SCHEMA
    override fun encode(value: GetContactOutput) = with(value.card) { buildJsonObject {
        put("card_id", id); put("name", name); put("name_en", nameEn); put("company", company)
        put("title", title); put("department", department); put("industry", industry)
        put("location", location); put("phone", phone); put("mobile", mobile); put("email", email)
        put("address", address); put("website", website); put("memo", memo)
        put("tags", JsonArray(tags.map(::JsonPrimitive))); put("updated_at", updatedAt)
    } }
}

private object UpdateInputCodec : ToolInputCodec<UpdateBusinessCardInput> {
    override val schema = UPDATE_INPUT_SCHEMA

    override fun decode(arguments: JsonObject): DecodeResult<UpdateBusinessCardInput> {
        val cardPrimitive = arguments["card_id"] as? JsonPrimitive
        if (cardPrimitive == null || !cardPrimitive.isString) return DecodeResult.Failure("명함 ID는 문자열이어야 합니다.", "card_id")
        val cardId = cardPrimitive.content.trim()
        if (cardId.isBlank()) return DecodeResult.Failure("명함 ID가 필요합니다.", "card_id")

        val updatesObject = arguments["updates"] as? JsonObject ?: JsonObject(emptyMap())
        val updates = linkedMapOf<String, String>()
        for ((field, value) in updatesObject) {
            if (field !in UPDATABLE_FIELDS) return DecodeResult.Failure("지원하지 않는 명함 필드입니다: $field", "updates")
            if (value != JsonNull) {
                val primitive = value as? JsonPrimitive
                val text = primitive?.takeIf { it.isString }?.content?.trim()
                    ?: return DecodeResult.Failure("수정 값은 문자열이어야 합니다: $field", "updates")
                updates[field] = text
            }
        }

        val clearFields = linkedSetOf<String>()
        val clearArray = arguments["clear_fields"] as? JsonArray
        if (clearArray != null) {
            clearArray.forEach { element: JsonElement ->
                val primitive = element as? JsonPrimitive
                if (primitive != null && !primitive.isString) return DecodeResult.Failure("clear_fields 값은 문자열이어야 합니다.", "clear_fields")
                val field = primitive?.content?.trim().orEmpty()
                if (field.isNotBlank()) {
                    if (field !in UPDATABLE_FIELDS) return DecodeResult.Failure("비울 수 없는 명함 필드입니다: $field", "clear_fields")
                    clearFields += field
                }
            }
        }

        if (updates.isEmpty() && clearFields.isEmpty()) {
            return DecodeResult.Failure("수정할 필드나 비울 필드를 입력해 주세요.")
        }
        return DecodeResult.Success(UpdateBusinessCardInput(cardId, updates, clearFields))
    }
}

private object UpdateOutputCodec : ToolOutputCodec<UpdateBusinessCardOutput> {
    override val schema = UPDATE_OUTPUT_SCHEMA

    override fun encode(value: UpdateBusinessCardOutput) = buildJsonObject {
        put("before", value.before.toJson())
        put("after", value.after.toJson())
    }
}

class SearchContactsPlugin(private val backend: ContactSearchBackend) :
    TypedToolPlugin<SearchContactsInput, SearchContactsOutput>(
        ToolImplementationId("ryeong.hybrid.local.v1"), ContactToolContracts.Search,
        SearchInputCodec, SearchOutputCodec,
    ) {
    override suspend fun availability() = if (backend.configurationAvailable()) ToolAvailability.Ready
        else ToolAvailability.Unavailable("contact.backend_initialization_failed")

    override suspend fun executeTyped(input: SearchContactsInput, request: ToolRequest, context: ToolExecutionContext): TypedToolResult<SearchContactsOutput> {
        val hits = backend.search(input.query, input.limit)
        val output = SearchContactsOutput(hits.map { hit -> with(hit.card) {
            SearchContactItem(id, name, company, title, location, hit.score)
        } }, backend.engineName())
        val ids = buildJsonObject { put("card_ids", JsonArray(output.results.map { JsonPrimitive(it.cardId) })) }
        return TypedToolResult.Success(output, sessionUpdates = listOf(
            SessionStateUpdate(SessionStateKey("contact", "last_search_results"), ContractVersion(1, 0), ids)
        ))
    }
}

class GetContactPlugin(private val backend: ContactSearchBackend) :
    TypedToolPlugin<GetContactInput, GetContactOutput>(
        ToolImplementationId("ryeong.lookup.local.v1"), ContactToolContracts.Get,
        GetInputCodec, GetOutputCodec,
    ) {
    override suspend fun availability() = if (backend.configurationAvailable()) ToolAvailability.Ready
        else ToolAvailability.Unavailable("contact.backend_initialization_failed")

    override suspend fun executeTyped(input: GetContactInput, request: ToolRequest, context: ToolExecutionContext): TypedToolResult<GetContactOutput> {
        val card = backend.get(input.cardId) ?: return TypedToolResult.Failure(ToolError(
            ToolErrorCode("contact.not_found"), "해당 명함을 찾을 수 없습니다.", false,
        ))
        val state = buildJsonObject { put("card_id", card.id) }
        return TypedToolResult.Success(GetContactOutput(card), sessionUpdates = listOf(
            SessionStateUpdate(SessionStateKey("contact", "selected_contact"), ContractVersion(1, 0), state)
        ))
    }
}

class UpdateBusinessCardPlugin(
    private val repository: MutableBusinessCardRepository,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val onUpdated: (BusinessCardUpdateResult) -> Unit = { },
) : TypedToolPlugin<UpdateBusinessCardInput, UpdateBusinessCardOutput>(
    ToolImplementationId("contact.update.local.v1"), ContactToolContracts.Update,
    UpdateInputCodec, UpdateOutputCodec,
) {
    override suspend fun availability() = if (repository.configurationAvailable()) ToolAvailability.Ready
        else ToolAvailability.Unavailable("contact.mutable_repository_unavailable")

    override suspend fun executeTyped(
        input: UpdateBusinessCardInput,
        request: ToolRequest,
        context: ToolExecutionContext,
    ): TypedToolResult<UpdateBusinessCardOutput> {
        val result = repository.update(input.cardId, input.updates, input.clearFields, nowUtcIsoString())
            ?: return TypedToolResult.Failure(ToolError(
                ToolErrorCode("contact.not_found"), "해당 명함을 찾을 수 없습니다.", false,
            ))
        onUpdated(result)
        return TypedToolResult.Success(
            UpdateBusinessCardOutput(result.before, result.after),
            userMessageKo = "명함을 수정했어요.",
            sessionUpdates = listOf(SessionStateUpdate(
                SessionStateKey("contact", "selected_contact"),
                ContractVersion(1, 0),
                buildJsonObject { put("card_id", result.after.id) },
            )),
        )
    }

    private fun nowUtcIsoString(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(clockMillis()))
}

private val UPDATABLE_FIELDS = setOf(
    "name", "name_en", "company", "department", "title", "industry", "location",
    "phone", "mobile", "email", "address", "website", "memo",
)

private fun BusinessCardRecord.toJson(): JsonObject = buildJsonObject {
    put("card_id", id); put("name", name); put("name_en", nameEn); put("company", company)
    put("title", title); put("department", department); put("industry", industry)
    put("location", location); put("phone", phone); put("mobile", mobile); put("email", email)
    put("address", address); put("website", website); put("memo", memo)
    put("tags", JsonArray(tags.map(::JsonPrimitive))); put("updated_at", updatedAt)
}

private fun MutableBusinessCardRepository.configurationAvailable(): Boolean = true
