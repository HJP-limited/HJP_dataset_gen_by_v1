package com.example.hjp

import androidx.room.Room
import android.os.Debug
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.example.hjp.search.AndroidEmbeddingGemmaEngine
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.android.AndroidIntentToolContracts
import com.hjp.tool.android.MessageChannel
import com.hjp.tool.android.MessageComposerBackend
import com.hjp.tool.android.MessageDraft
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RyeongRoomSearchInstrumentedTest {
    private lateinit var database: HjpDatabase
    private lateinit var repository: RoomBusinessCardRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, HjpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomBusinessCardRepository(context, database.businessCardDao(), Json)
        database.businessCardDao().insertAllAndReindex(cards())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun tieredFtsSupportsFieldsHonorificPhoneAndStableIds() = runBlocking {
        val cases = mapOf(
            "강서연씨 찾아줘" to "room-gang",
            "Seoyeon Kang" to "room-gang",
            "코어AI" to "room-gang",
            "AI 엔지니어" to "room-gang",
            "플랫폼팀" to "room-gang",
            "판교" to "room-gang",
            "머신러닝" to "room-gang",
            "인공지능" to "room-gang",
            "임베딩" to "room-gang",
            "01055554312" to "room-gang",
            "투자" to "room-jiwon",
        )
        val firstStarted = System.nanoTime()
        assertEquals("room-gang", repository.searchKeywordCandidates("강서연씨", 5).first().cardId)
        val firstQueryMillis = (System.nanoTime() - firstStarted) / 1_000_000.0
        cases.forEach { (query, expectedId) ->
            assertEquals(query, expectedId, repository.searchKeywordCandidates(query, 5).first().cardId)
        }
        assertTrue(repository.searchKeywordCandidates("존재하지않는검색", 5).isEmpty())
        assertEquals(1, repository.searchKeywordCandidates("AI", 1).size)
        val latencies = mutableListOf<Double>()
        repeat(100) { index ->
            val query = cases.keys.elementAt(index % cases.size)
            val started = System.nanoTime()
            repository.searchKeywordCandidates(query, 5)
            latencies += (System.nanoTime() - started) / 1_000_000.0
        }
        latencies.sort()
        val memoryInfo = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        println(
            "[AVD_FTS_EVAL] queries=${cases.size} repeats=100 success=100 " +
                "first_ms=$firstQueryMillis p50_ms=${latencies[50]} p95_ms=${latencies[95]} " +
                "total_pss_mb=${memoryInfo.totalPss / 1024.0}",
        )
    }

    @Test
    fun updateReindexesFtsAndPreservesCardId() = runBlocking {
        repository.update(
            "room-gang",
            updates = mapOf("company" to "새로운회사"),
            clearFields = emptySet(),
            updatedAt = "2026-07-31T00:00:00Z",
        )
        assertEquals(
            "room-gang",
            repository.searchKeywordCandidates("새로운회사", 5).single().cardId,
        )
        assertTrue(repository.searchKeywordCandidates("코어AI", 5).none { it.cardId == "room-gang" })
        assertEquals("room-gang", repository.getById("room-gang")?.id)
        database.businessCardDao().deleteAndReindex("room-gang")
        assertTrue(repository.searchKeywordCandidates("새로운회사", 5).isEmpty())
        assertEquals(null, repository.getById("room-gang"))
    }

    @Test
    fun unavailableNativeEmbeddingFallsBackToRealRoomKeywordSearch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = AndroidEmbeddingGemmaEngine(context)
        // A device prepared with the exact tokenizer exercises the separate model-backed test.
        assumeFalse(engine.isModelBacked)
        val backend = RyeongContactSearchBackend(
            repository,
            embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(engine) },
        )

        val response = backend.search("강서연씨", 5)

        assertEquals("KEYWORD_ONLY", response.mode)
        assertTrue(response.fallbackUsed)
        assertTrue(response.fallbackReason.isNotBlank())
        assertTrue(
            response.fallbackReason.contains("UNSUPPORTED_EMULATOR") ||
                response.fallbackReason.contains("sentencepiece.model"),
        )
        assertEquals("room-gang", response.hits.first().card.id)
        assertFalse(response.hits.first().retrievalSources.any { it.contains("semantic") })
        val detail = backend.get(response.hits.first().card.id)
        assertEquals("room-gang", detail?.id)
        assertEquals("room-gang@example.com", detail?.email)
        val resolvedDetail = requireNotNull(detail)
        var capturedDraft: MessageDraft? = null
        val compose = OpenComposePlugin(object : MessageComposerBackend {
            override fun isAvailable(channel: MessageChannel?) = true
            override suspend fun open(draft: MessageDraft): Boolean {
                capturedDraft = draft
                return true
            }
        }).execute(
            ToolRequest(
                "avd-keyword-compose",
                AndroidIntentToolContracts.Compose.capabilityId,
                AndroidIntentToolContracts.Compose.version,
                buildJsonObject {
                    put("channel", "email")
                    put("to", resolvedDetail.email)
                    put("subject", "AVD fallback 검증")
                    put("body", "작성 화면 호출까지만 검증합니다.")
                },
            ),
            ToolExecutionContext("avd-keyword", "turn", "ko-KR", "Asia/Seoul"),
        )
        assertTrue(compose is ToolExecutionResult.Success)
        assertEquals(resolvedDetail.email, capturedDraft?.to)
        engine.close()
    }

    private fun cards() = listOf(
        card(
            "room-gang", "강서연", "Seoyeon Kang", "코어AI", "AI 엔지니어",
            "플랫폼팀", "IT", "판교", "010-5555-4312", "머신러닝 검색 담당",
            "[\"임베딩\",\"개발\"]",
        ),
        card(
            "room-jiwon", "김지원", "Jiwon Kim", "비전글로벌", "대표",
            "전략팀", "금융", "서울", "010-0000-1111", "스타트업 투자 파트너십",
            "[\"투자\",\"대표\"]",
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
        id, name, nameEn, company, title, department, industry, location,
        phone = "", mobile = mobile, email = "$id@example.com", address = "비공개 주소",
        website = "", memo = memo, tagsJson = tags, updatedAt = "2026-07-31T00:00:00Z",
    )
}
