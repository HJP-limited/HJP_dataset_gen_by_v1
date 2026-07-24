package com.hjp.tool.contact

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared strict decoder used by Android assets and Desktop files. */
class BusinessCardJsonCodec(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decode(text: String): List<BusinessCardRecord> {
        val root = json.parseToJsonElement(text) as? JsonArray
            ?: error("Business card data must be a JSON array")
        val cards = root.mapIndexed { index, element ->
            val value = element as? JsonObject ?: error("Card at $index must be an object")
            value.toBusinessCard(index)
        }
        require(cards.map { it.id }.distinct().size == cards.size) { "Duplicate business card id" }
        return cards
    }

    private fun JsonObject.toBusinessCard(index: Int): BusinessCardRecord {
        fun text(key: String): String = (get(key) as? JsonPrimitive)?.content?.trim().orEmpty()
        fun required(key: String): String = text(key).takeIf(String::isNotBlank)
            ?: error("Card at $index is missing $key")
        val tags = (get("tags") as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)
        }.orEmpty()
        return BusinessCardRecord(
            id = required("id"), name = required("name"), nameEn = text("name_en"),
            company = text("company"), title = text("title"), department = text("department"),
            industry = text("industry"), location = text("location"), phone = text("phone"),
            mobile = text("mobile"), email = text("email"), address = text("address"),
            website = text("website"), memo = text("memo"), tags = tags, updatedAt = text("updated_at"),
        )
    }
}
