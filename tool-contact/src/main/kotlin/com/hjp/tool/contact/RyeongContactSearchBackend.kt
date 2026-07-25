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
    private val store: BusinessCardStore,
    private val embeddingEngineFactory: () -> EmbeddingEngine = { LocalEmbeddingEngine() },
) : ContactSearchBackend {
    private val initMutex = Mutex()
    @Volatile private var service: SearchLookupService? = null
    @Volatile private var indexedRevision: Long = Long.MIN_VALUE
    @Volatile private var initializationFailed = false

    override suspend fun search(query: String, limit: Int): List<ContactSearchHit> = withContext(Dispatchers.Default) {
        val response = requireService().retrieve(query, limit)
        trace(response)
        response.results.map { result ->
            with(result.card) {
                ContactSearchHit(
                    cardId = id,
                    name = name,
                    company = company,
                    title = title,
                    department = department,
                    industry = industry,
                    location = location,
                    tags = tags,
                    score = result.score,
                    breakdown = ContactScoreBreakdown(
                        keyword = result.breakdown.keywordScore,
                        semantic = result.breakdown.semanticScore,
                        rrf = result.rankFusionScore,
                    ),
                    matchedFields = result.matchedFields,
                    fallbackUsed = response.fallbackUsed,
                )
            }
        }
    }

    override suspend fun get(cardId: String): BusinessCardRecord? = withContext(Dispatchers.Default) {
        store.getById(cardId)
    }

    override fun engineName(): String = service?.engineName() ?: "ryeong-local"
    override fun configurationAvailable(): Boolean = !initializationFailed

    fun invalidate() {
        service = null
        indexedRevision = Long.MIN_VALUE
        initializationFailed = false
    }

    private suspend fun requireService(): SearchLookupService {
        val currentRevision = store.revision()
        service?.takeIf { indexedRevision == currentRevision }?.let { return it }
        return initMutex.withLock {
            val lockedRevision = store.revision()
            service?.takeIf { indexedRevision == lockedRevision }?.let { return@withLock it }
            try {
                val cards = store.loadAll()
                SearchLookupService(cards.map { it.toSearchCard() }, embeddingEngineFactory()).also {
                    service = it
                    indexedRevision = store.revision()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                initializationFailed = true
                throw error
            }
        }
    }

    private fun BusinessCardRecord.toSearchCard() = BusinessCard(
        id,
        name,
        nameEn,
        company,
        title,
        department,
        industry,
        location,
        phone,
        mobile,
        email,
        address,
        website,
        memo,
        tags,
        0L,
        runCatching { java.time.Instant.parse(updatedAt).toEpochMilli() }.getOrDefault(0L),
        "",
        "room",
        true,
    )

    private fun trace(response: com.hjp.searchlookup.RetrievalResponse) {
        if (System.getProperty("hjp.search.debug") != "true") return
        System.err.println("[SEARCH_BACKEND] ryeong")
        System.err.println("[QUERY_ANALYSIS] tokens=${response.queryAnalysis.tokens.size} strict_identity=${response.queryAnalysis.strictIdentityQuery}")
        System.err.println("[KEYWORD_RESULTS] count=${response.keywordResultCount}")
        System.err.println("[SEMANTIC_RESULTS] count=${response.semanticResultCount} engine=${response.embeddingModelName}")
        System.err.println("[RRF_RESULTS] count=${response.results.size} reranker=${response.rerankerName}")
        System.err.println("[RAG_CONTEXT] cards=${response.results.take(5).size} contact_details_included=false")
    }
}
