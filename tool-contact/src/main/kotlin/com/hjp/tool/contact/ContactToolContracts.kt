package com.hjp.tool.contact

import com.hjp.tool.contract.ConfirmationPolicy
import com.hjp.tool.contract.ContractVersion
import com.hjp.tool.contract.PiiLevel
import com.hjp.tool.contract.ToolCapabilityId
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolEffect
import com.hjp.tool.contract.ToolPresentation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal fun objectSchema(
    required: List<String>,
    properties: JsonObject,
): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("properties", properties)
    put("required", JsonArray(required.map(::JsonPrimitive)))
}

internal fun stringProperty(description: String, enum: List<String>? = null) = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
}

internal val SEARCH_INPUT_SCHEMA = objectSchema(listOf("query"), buildJsonObject {
    put("query", stringProperty("명함을 찾기 위한 한국어 검색어"))
    putJsonObject("limit") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", 10)
        put("default", 5)
    }
})

internal val SEARCH_OUTPUT_SCHEMA = objectSchema(listOf("results", "count", "engine"), buildJsonObject {
    putJsonObject("results") {
        put("type", "array")
        putJsonObject("items") {
            put("type", "object")
            putJsonObject("properties") {
                listOf("card_id", "name", "company", "title", "department", "industry", "location").forEach {
                    put(it, stringProperty(it))
                }
                putJsonObject("tags") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                putJsonObject("score") { put("type", "number") }
                putJsonObject("score_breakdown") {
                    put("type", "object")
                    putJsonObject("properties") {
                        listOf("keyword", "semantic", "rrf").forEach {
                            putJsonObject(it) { put("type", "number") }
                        }
                    }
                }
                putJsonObject("matched_fields") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                putJsonObject("fallback_used") { put("type", "boolean") }
            }
            put("required", JsonArray(listOf(
                "card_id", "name", "company", "title", "department", "industry", "location",
                "tags", "score", "score_breakdown", "matched_fields", "fallback_used",
            ).map(::JsonPrimitive)))
        }
    }
    putJsonObject("count") { put("type", "integer") }
    put("engine", stringProperty("검색 엔진"))
})

internal val GET_INPUT_SCHEMA = objectSchema(listOf("card_id"), buildJsonObject {
    put("card_id", stringProperty("검색 결과에서 반환된 명함 ID"))
    put("purpose", stringProperty("상세정보 조회 목적", listOf("display", "email", "sms", "calendar")))
})

internal val GET_OUTPUT_SCHEMA = objectSchema(
    listOf("card_id", "name", "company", "title", "department", "location", "phone", "mobile", "email", "address", "website", "memo", "tags", "updated_at"),
    buildJsonObject {
        listOf("card_id", "name", "name_en", "company", "title", "department", "industry", "location",
            "phone", "mobile", "email", "address", "website", "memo", "updated_at").forEach { put(it, stringProperty(it)) }
        putJsonObject("tags") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
    },
)

private val UPDATABLE_CONTACT_FIELDS = listOf(
    "name", "name_en", "company", "department", "title", "industry", "location",
    "phone", "mobile", "email", "address", "website", "memo",
)

internal val UPDATE_INPUT_SCHEMA = objectSchema(listOf("card_id"), buildJsonObject {
    put("card_id", stringProperty("수정할 명함 ID"))
    putJsonObject("updates") {
        put("type", "object")
        put("description", "변경할 필드와 새 값. 유지할 필드는 생략합니다.")
        put("additionalProperties", false)
        putJsonObject("properties") {
            UPDATABLE_CONTACT_FIELDS.forEach { put(it, stringProperty(it)) }
        }
    }
    putJsonObject("clear_fields") {
        put("type", "array")
        put("description", "값을 비울 필드 목록")
        put("items", stringProperty("비울 필드", UPDATABLE_CONTACT_FIELDS))
    }
})

internal val UPDATE_OUTPUT_SCHEMA = objectSchema(listOf("before", "after"), buildJsonObject {
    fun putCardObject(name: String) {
        putJsonObject(name) {
            put("type", "object")
            putJsonObject("properties") {
                listOf("card_id", "name", "name_en", "company", "title", "department", "industry", "location",
                    "phone", "mobile", "email", "address", "website", "memo", "updated_at").forEach {
                    put(it, stringProperty(it))
                }
                putJsonObject("tags") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            }
        }
    }
    putCardObject("before")
    putCardObject("after")
})

object ContactToolContracts {
    val Search = ToolContract(
        capabilityId = ToolCapabilityId("contact.search"),
        modelName = "search_contacts",
        version = ContractVersion(1, 0),
        description = "이름, 회사, 직함, 지역, 업종, 메모를 기준으로 로컬 명함을 검색합니다.",
        inputSchema = SEARCH_INPUT_SCHEMA,
        outputSchema = SEARCH_OUTPUT_SCHEMA,
        effect = ToolEffect.READ_ONLY,
        confirmationPolicy = ConfirmationPolicy.NONE,
        inputPii = PiiLevel.NONE,
        outputPii = PiiLevel.BASIC_CONTACT,
        defaultTimeoutMillis = 5_000,
        presentation = ToolPresentation("명함을 검색하고 있어요.", "명함 검색을 완료했어요.", "명함 검색을 사용할 수 없어요."),
    )

    val Get = ToolContract(
        capabilityId = ToolCapabilityId("contact.get"),
        modelName = "get_contact",
        version = ContractVersion(1, 0),
        description = "명함 ID로 선택한 명함의 전화번호, 이메일 등 상세정보를 조회합니다.",
        inputSchema = GET_INPUT_SCHEMA,
        outputSchema = GET_OUTPUT_SCHEMA,
        effect = ToolEffect.READ_ONLY,
        confirmationPolicy = ConfirmationPolicy.NONE,
        inputPii = PiiLevel.BASIC_CONTACT,
        outputPii = PiiLevel.SENSITIVE_CONTACT,
        defaultTimeoutMillis = 3_000,
        presentation = ToolPresentation("명함 상세정보를 확인하고 있어요.", "명함 상세정보를 확인했어요.", "명함 상세정보를 조회할 수 없어요."),
    )

    val Update = ToolContract(
        capabilityId = ToolCapabilityId("contact.update"),
        modelName = "update_business_card",
        version = ContractVersion(1, 0),
        description = "로컬 명함의 일부 필드를 수정하거나 비웁니다. 반드시 대상 명함을 검색/조회로 특정한 뒤 사용하세요.",
        inputSchema = UPDATE_INPUT_SCHEMA,
        outputSchema = UPDATE_OUTPUT_SCHEMA,
        effect = ToolEffect.LOCAL_MUTATION,
        confirmationPolicy = ConfirmationPolicy.BEFORE_EXECUTION,
        inputPii = PiiLevel.SENSITIVE_CONTACT,
        outputPii = PiiLevel.SENSITIVE_CONTACT,
        defaultTimeoutMillis = 5_000,
        presentation = ToolPresentation("명함을 수정하고 있어요.", "명함을 수정했어요.", "명함 수정 기능을 사용할 수 없어요."),
    )
}
