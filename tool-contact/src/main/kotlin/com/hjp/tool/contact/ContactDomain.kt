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

interface BusinessCardRepository {
    suspend fun loadAll(): List<BusinessCardRecord>
    suspend fun getById(cardId: String): BusinessCardRecord?
}

data class BusinessCardUpdateResult(
    val before: BusinessCardRecord,
    val after: BusinessCardRecord,
)

interface MutableBusinessCardRepository : BusinessCardRepository {
    suspend fun update(
        cardId: String,
        updates: Map<String, String>,
        clearFields: Set<String>,
        updatedAt: String,
    ): BusinessCardUpdateResult?
}

data class ContactSearchHit(val card: BusinessCardRecord, val score: Double)

interface ContactSearchBackend {
    suspend fun search(query: String, limit: Int): List<ContactSearchHit>
    suspend fun get(cardId: String): BusinessCardRecord?
    fun engineName(): String
    fun configurationAvailable(): Boolean
}
