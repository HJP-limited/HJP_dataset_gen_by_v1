package com.hjp.tool.contact

import com.hjp.searchlookup.BusinessCard
import com.hjp.searchlookup.EmbeddingEngine
import com.hjp.searchlookup.LocalEmbeddingEngine
import com.hjp.searchlookup.SearchLookupService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RyeongContactSearchBackend(
    private val repository: BusinessCardRepository,
    private val embeddingEngineFactory: () -> EmbeddingEngine = { LocalEmbeddingEngine() },
) : ContactSearchBackend {
    private val initMutex = Mutex()
    @Volatile private var service: SearchLookupService? = null
    @Volatile private var initializationFailed = false

    override suspend fun search(query: String, limit: Int): List<ContactSearchHit> = withContext(Dispatchers.Default) {
        requireService().search(query, limit).map { ContactSearchHit(it.card.toRecord(), it.score) }
    }

    override suspend fun get(cardId: String): BusinessCardRecord? = withContext(Dispatchers.Default) {
        repository.getById(cardId)
    }

    override fun engineName(): String = service?.engineName() ?: "ryeong-local"
    override fun configurationAvailable(): Boolean = !initializationFailed

    fun invalidate() {
        service = null
        initializationFailed = false
    }

    private suspend fun requireService(): SearchLookupService {
        service?.let { return it }
        return initMutex.withLock {
            service?.let { return@withLock it }
            try {
                val cards = repository.loadAll()
                require(cards.isNotEmpty()) { "Business card dataset is empty" }
                SearchLookupService(cards.map { it.toRyeongCard() }, embeddingEngineFactory()).also { service = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                initializationFailed = true
                throw error
            }
        }
    }

    private fun BusinessCardRecord.toRyeongCard() = BusinessCard(
        id,
        name,
        nameEn,
        company,
        title,
        department,
        industry,
        location,
        listOf(phone, mobile).filter(String::isNotBlank).joinToString(" "),
        email,
        listOf(address, website).filter(String::isNotBlank).joinToString(" "),
        memo,
        tags,
    )

    private fun BusinessCard.toRecord() = BusinessCardRecord(
        id = id,
        name = name,
        nameEn = nameEn,
        company = company,
        title = title,
        department = department,
        industry = industry,
        location = location,
        phone = phone,
        email = email,
        address = address,
        memo = memo,
        tags = tags,
    )
}
