package com.hjp.tool.contact

data class BusinessCardRecord(
    val id: String,
    val name: String,
    val nameEn: String = "",
    val company: String = "",
    val title: String = "",
    val department: String = "",
    val industry: String = "",
    val location: String = "",
    val phone: String = "",
    val mobile: String = "",
    val email: String = "",
    val address: String = "",
    val website: String = "",
    val memo: String = "",
    val tags: List<String> = emptyList(),
    val updatedAt: String = "",
)

/** Original/detail store. Search indexing has one separate canonical Ryeong repository boundary. */
interface BusinessCardStore {
    suspend fun loadAll(): List<BusinessCardRecord>
    suspend fun getById(cardId: String): BusinessCardRecord?
    suspend fun revision(): Long = 0L
}

data class BusinessCardUpdateResult(
    val before: BusinessCardRecord,
    val after: BusinessCardRecord,
)

interface MutableBusinessCardStore : BusinessCardStore {
    suspend fun update(
        cardId: String,
        updates: Map<String, String>,
        clearFields: Set<String>,
        updatedAt: String,
    ): BusinessCardUpdateResult?

    suspend fun upsert(card: BusinessCardRecord) {
        error("Business card creation is not supported by this store")
    }

    suspend fun delete(cardId: String): Boolean {
        error("Business card deletion is not supported by this store")
    }
}

data class ContactScoreBreakdown(
    val keyword: Double,
    val semantic: Double,
    val rrf: Double,
)

/** Privacy-minimized search projection. Contact details are intentionally absent. */
data class ContactSearchHit(
    val cardId: String,
    val name: String,
    val company: String,
    val title: String,
    val department: String,
    val industry: String,
    val location: String,
    val tags: List<String>,
    val score: Double,
    val breakdown: ContactScoreBreakdown,
    val matchedFields: List<String>,
    val fallbackUsed: Boolean,
)

interface ContactSearchBackend {
    suspend fun search(query: String, limit: Int): List<ContactSearchHit>
    suspend fun get(cardId: String): BusinessCardRecord?
    fun engineName(): String
    fun configurationAvailable(): Boolean
}
