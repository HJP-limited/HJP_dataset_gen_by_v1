package com.example.hjp.eval.ryeong

import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The frozen 1,000-card evaluation fixture, loaded without alteration.
 *
 * IDs are carried through untouched: no prefixing, no renumbering, no de-duplication. If the file
 * and the production record type ever disagree about a field, that has to surface as a load
 * failure rather than as a silently empty column.
 */
object RyeongCards {

    const val RESOURCE = "/ryeong/cards_eval1000.json"
    const val EXPECTED_COUNT = 1000

    private val json = Json { ignoreUnknownKeys = true }

    fun loadRaw(): String = requireNotNull(
        RyeongCards::class.java.getResourceAsStream(RESOURCE),
    ) { "frozen card fixture missing: $RESOURCE" }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun load(): List<BusinessCardRecord> {
        val array = json.parseToJsonElement(loadRaw()) as? JsonArray
            ?: error("card fixture is not a JSON array")
        val cards = array.map { element ->
            val card = element as? JsonObject ?: error("card entry is not an object")
            BusinessCardRecord(
                id = card.str("id"),
                name = card.str("name"),
                nameEn = card.strOrEmpty("nameEn"),
                company = card.strOrEmpty("company"),
                title = card.strOrEmpty("title"),
                department = card.strOrEmpty("department"),
                industry = card.strOrEmpty("industry"),
                location = card.strOrEmpty("location"),
                phone = card.strOrEmpty("phone"),
                email = card.strOrEmpty("email"),
                address = card.strOrEmpty("address"),
                memo = card.strOrEmpty("memo"),
                tags = (card["tags"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty(),
            )
        }
        require(cards.size == EXPECTED_COUNT) {
            "card fixture holds ${cards.size} cards, expected $EXPECTED_COUNT"
        }
        val duplicated = cards.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "duplicate card ids: $duplicated" }
        return cards
    }

    /** Per-field checksum, so a mapping that quietly drops a column is visible. */
    fun fieldChecksums(cards: List<BusinessCardRecord>): Map<String, String> {
        fun digest(selector: (BusinessCardRecord) -> String): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            cards.forEach { md.update((selector(it) + "").toByteArray(Charsets.UTF_8)) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        return linkedMapOf(
            "id" to digest { it.id },
            "name" to digest { it.name },
            "company" to digest { it.company },
            "title" to digest { it.title },
            "department" to digest { it.department },
            "location" to digest { it.location },
            "phone" to digest { it.phone },
            "email" to digest { it.email },
            "address" to digest { it.address },
        )
    }

    private fun JsonObject.str(key: String): String {
        val p = this[key] as? JsonPrimitive ?: error("missing '$key'")
        require(p.isString) { "'$key' must be a JSON string" }
        return p.content
    }

    private fun JsonObject.strOrEmpty(key: String): String {
        val e = this[key] ?: return ""
        if (e is JsonNull) return ""
        val p = e as? JsonPrimitive ?: return ""
        return p.content
    }
}
