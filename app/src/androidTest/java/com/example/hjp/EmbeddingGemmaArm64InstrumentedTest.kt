package com.example.hjp

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.example.hjp.search.AndroidEmbeddingGemmaEngine
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.android.AndroidMessageComposerBackend
import com.hjp.tool.android.AndroidIntentToolContracts
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Run explicitly on a physical ARM64 device; every AVD skips and is never counted as semantic. */
@RunWith(AndroidJUnit4::class)
class EmbeddingGemmaArm64InstrumentedTest {
    @Test
    fun modelBackedSearchGetAndComposeDraftE2e() = runBlocking {
        assumeTrue(Build.SUPPORTED_ABIS.any { it.startsWith("arm64") })
        assumeFalse(
            Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
                Build.MODEL.contains("Emulator", ignoreCase = true) ||
                Build.PRODUCT.contains("sdk_gphone", ignoreCase = true),
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = AndroidEmbeddingGemmaEngine(context)
        assertTrue(engine.diagnosticStatus(), engine.isModelBacked)
        val query = engine.embedQuery("온디바이스 인공지능 의미 검색 전문가")
        val document = engine.embedDocument("코어AI AI 엔지니어 임베딩 검색")
        assertEquals(768, query.size)
        assertEquals(768, document.size)
        assertTrue(query.all(Float::isFinite) && query.any { it != 0f })
        assertTrue(document.all(Float::isFinite) && document.any { it != 0f })

        val repository = RoomBusinessCardRepository(
            context,
            HjpDatabase.getInstance(context).businessCardDao(),
        )
        val backend = RyeongContactSearchBackend(
            repository,
            embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(engine) },
        )
        val response = backend.search("인공지능 임베딩 검색 전문가", 5)
        assertEquals("HYBRID", response.mode)
        assertFalse(response.fallbackUsed)
        val hit = response.hits.first { it.card.id == "C002" }
        assertTrue(hit.retrievalSources.any { it.contains("semantic") })
        val detail = backend.get(hit.card.id)
        assertEquals("C002", detail?.id)
        assertTrue(detail?.email?.isNotBlank() == true)

        val compose = OpenComposePlugin(AndroidMessageComposerBackend(context)).execute(
            ToolRequest(
                "arm64-e2e-compose",
                AndroidIntentToolContracts.Compose.capabilityId,
                AndroidIntentToolContracts.Compose.version,
                buildJsonObject {
                    put("channel", "email")
                    put("to", detail!!.email)
                    put("subject", "검색 통합 검증")
                    put("body", "작성 화면만 열고 실제 전송하지 않는 검증입니다.")
                },
            ),
            ToolExecutionContext("arm64-e2e", "turn", "ko-KR", "Asia/Seoul"),
        )
        assertTrue(compose is ToolExecutionResult.Success)
        engine.close()
    }
}
