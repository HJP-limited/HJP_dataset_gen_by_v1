package com.example.hjp

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.example.hjp.search.AndroidEmbeddingGemmaEngine
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.ContactToolContracts
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contact.SearchDiagnostics
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real search_contacts tool call through the production native semantic/hybrid stack. */
@RunWith(AndroidJUnit4::class)
class SearchContactsSemanticHybridInstrumentedTest {
    @Test
    fun searchContactsUsesProductionEmbeddingGemmaHybridRetrievalWithoutFallback() = runBlocking {
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        assumeFalse(AppContainer.isAndroidEmulator())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, HjpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        var embeddingEngine: AndroidEmbeddingGemmaEngine? = null

        try {
            val repository = RoomBusinessCardRepository(context, database.businessCardDao(), Json)
            database.businessCardDao().insertAllAndReindex(cards())

            embeddingEngine = AndroidEmbeddingGemmaEngine(context)
            assertTrue(embeddingEngine.diagnosticStatus(), embeddingEngine.isModelBacked)
            val onDevice = OnDeviceEmbeddingEngine.production(embeddingEngine)
            assertTrue(onDevice.diagnosticStatus(), onDevice.isModelBacked)
            assertFalse(onDevice.fallbackReason(), onDevice.isFallbackUsed)

            var observed: SearchDiagnostics? = null
            val backend = RyeongContactSearchBackend(
                repository = repository,
                embeddingEngineFactory = { onDevice },
                diagnostics = { observed = it },
            )
            val plugin = SearchContactsPlugin(backend)
            assertEquals("search_contacts", plugin.contract.modelName)

            val result = plugin.execute(
                ToolRequest(
                    callId = "device-semantic-search-1",
                    capabilityId = ContactToolContracts.Search.capabilityId,
                    contractVersion = ContactToolContracts.Search.version,
                    arguments = buildJsonObject {
                        put("query", QUERY)
                        put("limit", 5)
                    },
                ),
                ToolExecutionContext(
                    sessionId = "device-semantic-session",
                    turnId = "device-semantic-turn",
                    localeTag = "ko-KR",
                    deviceTimeZoneId = "Asia/Seoul",
                ),
            )

            assertTrue("search_contacts failed: $result", result is ToolExecutionResult.Success)
            val success = result as ToolExecutionResult.Success
            assertEquals("HYBRID", success.data.getValue("mode").jsonPrimitive.content)
            assertFalse(success.data.getValue("fallback_used").jsonPrimitive.boolean)
            assertTrue(
                success.data.getValue("engine").jsonPrimitive.content,
                success.data.getValue("engine").jsonPrimitive.content.startsWith(
                    AndroidEmbeddingGemmaEngine.MODEL_NAME,
                ),
            )
            val toolResults = success.data.getValue("results").jsonArray
            assertTrue(toolResults.isNotEmpty())
            assertTrue(toolResults.any {
                it.jsonObject.getValue("card_id").jsonPrimitive.content == EXPECTED_CARD_ID
            })

            val diagnostics = observed
            assertNotNull("Ryeong backend emitted no diagnostics", diagnostics)
            requireNotNull(diagnostics)
            assertEquals("HYBRID", diagnostics.mode)
            assertFalse(diagnostics.fallbackReason, diagnostics.fallbackUsed)
            assertTrue("keyword candidates were not produced: $diagnostics", diagnostics.keywordResultCount > 0)
            assertTrue("semantic candidates were not produced: $diagnostics", diagnostics.semanticResultCount > 0)
            assertTrue("native query embedding was not timed: $diagnostics", diagnostics.queryEmbeddingMillis > 0)
            assertTrue(diagnostics.rankedCardIds.contains(EXPECTED_CARD_ID))
            assertTrue(
                "no fused keyword+semantic result: ${diagnostics.rankings}",
                diagnostics.rankings.any { ranking ->
                    "keyword" in ranking.sources &&
                        "semantic" in ranking.sources &&
                        ranking.keywordRank > 0 &&
                        ranking.semanticRank > 0 &&
                        ranking.semanticSimilarity > 0.0
                },
            )
            assertTrue(onDevice.diagnosticStatus(), onDevice.isModelBacked)
            assertFalse(onDevice.fallbackReason(), onDevice.isFallbackUsed)
            assertTrue(embeddingEngine.isModelBacked)

            val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            Log.i(
                TAG,
                "SEARCH_CONTACTS_SEMANTIC_HYBRID_VERIFIED pid=${Process.myPid()} " +
                    "tool=${plugin.contract.modelName} mode=${diagnostics.mode} fallback=false " +
                    "engine=${diagnostics.engine} keyword_candidates=${diagnostics.keywordResultCount} " +
                    "semantic_candidates=${diagnostics.semanticResultCount} " +
                    "query_embedding_ms=${diagnostics.queryEmbeddingMillis} " +
                    "initialization_ms=${diagnostics.initializationMillis} " +
                    "elapsed_ms=${diagnostics.elapsedMillis} result_ids=${diagnostics.rankedCardIds} " +
                    "total_pss_kb=${memory.totalPss} native_pss_kb=${memory.nativePss}",
            )
            diagnostics.rankings.forEach { ranking ->
                Log.i(
                    TAG,
                    "RANK card_id=${ranking.cardId} final=${ranking.finalRank} " +
                        "keyword=${ranking.keywordRank} semantic=${ranking.semanticRank} " +
                        "similarity=${ranking.semanticSimilarity} sources=${ranking.sources}",
                )
            }
        } finally {
            embeddingEngine?.close()
            database.close()
            Log.i(TAG, "SEARCH_CONTACTS_NATIVE_RESOURCES_CLOSED pid=${Process.myPid()}")
        }
        Unit
    }

    private fun cards() = listOf(
        card(
            id = EXPECTED_CARD_ID,
            name = "강서연",
            nameEn = "Seoyeon Kang",
            company = "코어AI",
            title = "AI 엔지니어",
            department = "플랫폼팀",
            industry = "IT",
            location = "판교",
            mobile = "010-5555-4312",
            memo = "머신러닝 검색과 온디바이스 임베딩 담당",
            tags = "[\"임베딩\",\"인공지능\",\"개발\"]",
        ),
        card(
            id = "semantic-hybrid-jiwon",
            name = "김지원",
            nameEn = "Jiwon Kim",
            company = "비전글로벌",
            title = "대표",
            department = "전략팀",
            industry = "금융",
            location = "서울",
            mobile = "010-0000-1111",
            memo = "스타트업 투자 파트너십",
            tags = "[\"투자\",\"대표\"]",
        ),
    )

    private fun card(
        id: String,
        name: String,
        nameEn: String,
        company: String,
        title: String,
        department: String,
        industry: String,
        location: String,
        mobile: String,
        memo: String,
        tags: String,
    ) = BusinessCardEntity(
        id = id,
        name = name,
        nameEn = nameEn,
        company = company,
        title = title,
        department = department,
        industry = industry,
        location = location,
        phone = "",
        mobile = mobile,
        email = "$id@example.com",
        address = "비공개 주소",
        website = "",
        memo = memo,
        tagsJson = tags,
        updatedAt = "2026-08-29T00:00:00Z",
    )

    private companion object {
        const val TAG = "HjpSearchContactsVerify"
        const val QUERY = "인공지능 임베딩 검색 전문가"
        const val EXPECTED_CARD_ID = "semantic-hybrid-gang"
    }
}
