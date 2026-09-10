package com.example.hjp

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.example.hjp.search.AndroidEmbeddingGemmaEngine
import com.hjp.agent.core.ContactNameCandidates
import com.hjp.searchlookup.BusinessCard
import com.hjp.searchlookup.EmbeddingEngine
import com.hjp.searchlookup.EmbeddingUpdater
import com.hjp.searchlookup.FloatVectorCodec
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.ContactToolContracts
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contact.SearchDiagnostics
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRequest
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Destructive-to-the-test-window parity gate for the actual persisted production Room database.
 * The host runner must back up and restore hjp-agent.db around this test.
 */
@RunWith(AndroidJUnit4::class)
class ProductionRoom1000ParityInstrumentedTest {
    @Test
    fun persistedRoomFtsStoredVectorsHybridAndUpdateInvalidationWorkFor1000Cards() = runBlocking {
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        assumeFalse(AppContainer.isAndroidEmulator())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val datasetBytes = instrumentation.context.assets.open(CARD_ASSET).use { it.readBytes() }
        assertEquals(EXPECTED_DATASET_SHA256, sha256(datasetBytes))
        val cards = parseCards(String(datasetBytes, Charsets.UTF_8))
        assertEquals(EXPECTED_CARDS, cards.size)
        assertEquals(EXPECTED_CARDS, cards.map { it.id }.distinct().size)

        val database = HjpDatabase.getInstance(context)
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        val dao = database.businessCardDao()
        clearProductionTables(database)
        dao.insertAllAndReindex(cards)
        assertTrue(databaseFile.absolutePath, databaseFile.isFile)
        assertEquals(EXPECTED_CARDS, dao.count())
        assertEquals(EXPECTED_CARDS, tableCount(database, "business_cards_fts"))
        assertEquals("ok", integrityCheck(database))

        val repository = RoomBusinessCardRepository(context, dao, Json)
        assertEquals(EXPECTED_CARDS, repository.loadAll().size)
        assertTrue(repository.searchKeywordCandidates(INITIAL_QUERY, 20).any { it.cardId == TARGET_CARD_ID })

        val nativeEngine = AndroidEmbeddingGemmaEngine(context)
        assertTrue(nativeEngine.diagnosticStatus(), nativeEngine.isModelBacked)
        val onDevice = OnDeviceEmbeddingEngine.production(nativeEngine)
        val measuredEngine = CountingEmbeddingEngine(onDevice)
        val diagnostics = mutableListOf<SearchDiagnostics>()
        val backend = RyeongContactSearchBackend(
            repository = repository,
            embeddingEngineFactory = { measuredEngine },
            diagnostics = { event ->
                diagnostics += event
                Log.i(
                    TAG,
                    "SEARCH phase=${diagnostics.size} mode=${event.mode} fallback=${event.fallbackUsed} " +
                        "keyword=${event.keywordResultCount} semantic=${event.semanticResultCount} " +
                        "query_embedding_ms=${event.queryEmbeddingMillis} init_ms=${event.initializationMillis} " +
                        "elapsed_ms=${event.elapsedMillis} ids=${event.rankedCardIds.take(10)}",
                )
            },
        )
        val directory = RepositoryContactDirectory(repository)
        var invalidationCallbacks = 0
        val searchPlugin = SearchContactsPlugin(backend)
        val updatePlugin = UpdateBusinessCardPlugin(
            repository = repository,
            onUpdated = {
                invalidationCallbacks += 1
                backend.invalidate()
                directory.invalidate()
                Log.i(TAG, "UPDATE_INVALIDATION_CALLBACK count=$invalidationCallbacks")
            },
        )

        try {
            Log.i(
                TAG,
                "ROOM_1000_START pid=${Process.myPid()} db=${databaseFile.absolutePath} " +
                    "db_bytes=${databaseFile.length()} dataset_sha256=${sha256(datasetBytes)} cards=${cards.size} " +
                    "fts_rows=${tableCount(database, "business_cards_fts")} engine=${measuredEngine.name()}",
            )

            val first = executeSearch(searchPlugin, "initial", INITIAL_QUERY)
            assertHybrid(first, diagnostics.last(), TARGET_CARD_ID)
            assertEquals(EXPECTED_CARDS, measuredEngine.documentCalls)
            assertEquals(1, measuredEngine.queryCalls)

            val storedBefore = repository.loadEmbeddings(measuredEngine.name())
            verifyStoredEmbeddings(storedBefore)
            assertEquals(EXPECTED_CARDS, tableCount(database, "card_embeddings"))
            val targetBefore = storedBefore.single { it.cardId == TARGET_CARD_ID }
            val controlBefore = storedBefore.single { it.cardId == CONTROL_CARD_ID }
            val targetBeforeVector = targetBefore.vectorBlob.copyOf()
            val controlBeforeVector = controlBefore.vectorBlob.copyOf()
            Log.i(
                TAG,
                "STORED_EMBEDDINGS_CREATED rows=${storedBefore.size} dimension=768 " +
                    "blob_bytes=${targetBefore.vectorBlob.size} document_calls=${measuredEngine.documentCalls}",
            )

            backend.invalidate()
            val cached = executeSearch(searchPlugin, "cached", INITIAL_QUERY)
            assertHybrid(cached, diagnostics.last(), TARGET_CARD_ID)
            assertEquals("stored vectors were regenerated", EXPECTED_CARDS, measuredEngine.documentCalls)
            assertEquals(2, measuredEngine.queryCalls)
            Log.i(TAG, "STORED_EMBEDDING_CACHE_REUSED regenerated_documents=0")

            val originalTarget = requireNotNull(repository.getById(TARGET_CARD_ID))
            assertTrue(
                directory.resolve(ContactNameCandidates.candidates("${originalTarget.name}씨 찾아줘"))
                    .any { TARGET_CARD_ID in it.cardIds },
            )

            val update = updatePlugin.execute(
                ToolRequest(
                    callId = "room-1000-update",
                    capabilityId = ContactToolContracts.Update.capabilityId,
                    contractVersion = ContactToolContracts.Update.version,
                    arguments = buildJsonObject {
                        put("card_id", TARGET_CARD_ID)
                        putJsonObject("updates") {
                            put("name", UPDATED_NAME)
                            put("company", UPDATED_COMPANY)
                            put("memo", UPDATED_MEMO)
                        }
                    },
                ),
                toolContext("update-turn"),
            )
            assertTrue("update_business_card failed: $update", update is ToolExecutionResult.Success)
            assertEquals(1, invalidationCallbacks)

            assertTrue(repository.searchKeywordCandidates(UPDATED_COMPANY, 20).any { it.cardId == TARGET_CARD_ID })
            assertFalse(repository.searchKeywordCandidates(originalTarget.company, 20).any { it.cardId == TARGET_CARD_ID })
            assertFalse(
                directory.resolve(ContactNameCandidates.candidates("${originalTarget.name}씨 찾아줘"))
                    .any { TARGET_CARD_ID in it.cardIds },
            )
            assertTrue(
                directory.resolve(ContactNameCandidates.candidates("${UPDATED_NAME}씨 찾아줘"))
                    .any { TARGET_CARD_ID in it.cardIds },
            )
            assertEquals(EXPECTED_CARDS, tableCount(database, "business_cards_fts"))
            Log.i(TAG, "ROOM_FTS_AND_DIRECTORY_INVALIDATED target=$TARGET_CARD_ID")

            val updatedSearch = executeSearch(searchPlugin, "updated", UPDATED_QUERY)
            assertHybrid(updatedSearch, diagnostics.last(), TARGET_CARD_ID)
            assertEquals("exactly one changed card must be re-embedded", EXPECTED_CARDS + 1, measuredEngine.documentCalls)
            assertEquals(3, measuredEngine.queryCalls)

            val storedAfter = repository.loadEmbeddings(measuredEngine.name())
            verifyStoredEmbeddings(storedAfter)
            val targetAfter = storedAfter.single { it.cardId == TARGET_CARD_ID }
            val controlAfter = storedAfter.single { it.cardId == CONTROL_CARD_ID }
            assertEquals(targetBefore.createdAtMillis, targetAfter.createdAtMillis)
            assertTrue(targetAfter.updatedAtMillis >= targetBefore.updatedAtMillis)
            assertNotEquals(targetBefore.sourceTextHash, targetAfter.sourceTextHash)
            assertFalse(targetBeforeVector.contentEquals(targetAfter.vectorBlob))
            assertEquals(controlBefore.sourceTextHash, controlAfter.sourceTextHash)
            assertTrue(controlBeforeVector.contentEquals(controlAfter.vectorBlob))
            assertEquals(EXPECTED_CARDS, tableCount(database, "card_embeddings"))

            val updatedRecord = requireNotNull(repository.getById(TARGET_CARD_ID))
            assertEquals(
                EmbeddingUpdater.sha256(updatedRecord.toSearchCard().searchableText()),
                targetAfter.sourceTextHash,
            )
            assertTrue(onDevice.isModelBacked)
            assertFalse(onDevice.fallbackReason(), onDevice.isFallbackUsed)
            assertEquals("ok", integrityCheck(database))

            val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            Log.i(
                TAG,
                "PRODUCTION_ROOM_1000_PARITY_VERIFIED pid=${Process.myPid()} cards=${dao.count()} " +
                    "fts_rows=${tableCount(database, "business_cards_fts")} stored_vectors=${storedAfter.size} " +
                    "document_calls=${measuredEngine.documentCalls} query_calls=${measuredEngine.queryCalls} " +
                    "update_reembedded=1 mode=${diagnostics.last().mode} fallback=false " +
                    "total_pss_kb=${memory.totalPss} native_pss_kb=${memory.nativePss}",
            )
        } finally {
            nativeEngine.close()
            Log.i(TAG, "ROOM_1000_NATIVE_ENGINE_CLOSED pid=${Process.myPid()}")
        }
        Unit
    }

    private suspend fun executeSearch(
        plugin: SearchContactsPlugin,
        phase: String,
        query: String,
    ): ToolExecutionResult.Success {
        val started = SystemClock.elapsedRealtime()
        val result = plugin.execute(
            ToolRequest(
                callId = "room-1000-$phase",
                capabilityId = ContactToolContracts.Search.capabilityId,
                contractVersion = ContactToolContracts.Search.version,
                arguments = buildJsonObject {
                    put("query", query)
                    put("limit", 10)
                },
            ),
            toolContext("$phase-turn"),
        )
        assertTrue("search_contacts $phase failed: $result", result is ToolExecutionResult.Success)
        return (result as ToolExecutionResult.Success).also {
            Log.i(TAG, "TOOL phase=$phase duration_ms=${SystemClock.elapsedRealtime() - started}")
        }
    }

    private fun assertHybrid(
        result: ToolExecutionResult.Success,
        diagnostics: SearchDiagnostics,
        expectedCardId: String,
    ) {
        assertEquals("HYBRID", result.data.getValue("mode").jsonPrimitive.content)
        assertFalse(result.data.getValue("fallback_used").jsonPrimitive.boolean)
        assertTrue(result.data.getValue("results").jsonArray.any {
            it.jsonObject.getValue("card_id").jsonPrimitive.content == expectedCardId
        })
        assertEquals("HYBRID", diagnostics.mode)
        assertFalse(diagnostics.fallbackReason, diagnostics.fallbackUsed)
        assertTrue(diagnostics.keywordResultCount > 0)
        assertTrue(diagnostics.semanticResultCount > 0)
        assertTrue(diagnostics.queryEmbeddingMillis > 0)
        assertTrue(diagnostics.rankings.any {
            it.cardId == expectedCardId &&
                it.keywordRank > 0 &&
                it.semanticRank > 0 &&
                "keyword" in it.sources &&
                "semantic" in it.sources
        })
    }

    private fun verifyStoredEmbeddings(embeddings: List<com.hjp.tool.contact.StoredCardEmbedding>) {
        assertEquals(EXPECTED_CARDS, embeddings.size)
        embeddings.forEach { stored ->
            assertEquals(768, stored.dimension)
            assertEquals(768 * Float.SIZE_BYTES, stored.vectorBlob.size)
            assertTrue(stored.sourceTextHash.matches(Regex("[0-9a-f]{64}")))
            val vector = FloatVectorCodec.fromBlob(stored.vectorBlob)
            assertEquals(768, vector.size)
            assertTrue(vector.all(Float::isFinite))
            assertTrue(vector.any { it != 0f })
        }
    }

    private fun clearProductionTables(database: HjpDatabase) {
        val db = database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM card_embeddings")
            db.execSQL("DELETE FROM business_cards_fts")
            db.execSQL("DELETE FROM business_cards")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun tableCount(database: HjpDatabase, table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun integrityCheck(database: HjpDatabase): String =
        database.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun parseCards(raw: String): List<BusinessCardEntity> =
        (Json.parseToJsonElement(raw) as JsonArray).map { element ->
            val card = element as JsonObject
            fun text(key: String) = (card[key] as? JsonPrimitive)?.content.orEmpty()
            val tags = text("tags").split(',').map(String::trim).filter(String::isNotEmpty)
            BusinessCardEntity(
                id = text("id"),
                name = text("name"),
                nameEn = text("nameEn"),
                company = text("company"),
                title = text("title"),
                department = text("department"),
                industry = text("industry"),
                location = text("location"),
                phone = text("phone"),
                mobile = "",
                email = text("email"),
                address = text("address"),
                website = "",
                memo = text("memo"),
                tagsJson = Json.encodeToString(tags),
                updatedAt = "",
            )
        }

    private fun com.hjp.tool.contact.BusinessCardRecord.toSearchCard() = BusinessCard(
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
        address,
        memo,
        tags,
    )

    private fun toolContext(turnId: String) = ToolExecutionContext(
        sessionId = "production-room-1000",
        turnId = turnId,
        localeTag = "ko-KR",
        deviceTimeZoneId = "Asia/Seoul",
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class CountingEmbeddingEngine(
        private val delegate: OnDeviceEmbeddingEngine,
    ) : EmbeddingEngine {
        var queryCalls: Int = 0
            private set
        var documentCalls: Int = 0
            private set

        override fun embed(input: String): FloatArray = embedQuery(input)

        override fun embedQuery(input: String): FloatArray {
            queryCalls += 1
            return delegate.embedQuery(input)
        }

        override fun embedDocument(input: String): FloatArray {
            documentCalls += 1
            if (documentCalls == 1 || documentCalls % 100 == 0) {
                Log.i(TAG, "DOCUMENT_EMBEDDING_PROGRESS count=$documentCalls")
            }
            return delegate.embedDocument(input)
        }

        override fun name(): String = delegate.name()
        override fun isModelBacked(): Boolean = delegate.isModelBacked
        override fun diagnosticStatus(): String = delegate.diagnosticStatus()
    }

    private companion object {
        const val TAG = "HjpRoom1000Parity"
        const val DATABASE_NAME = "hjp-agent.db"
        const val CARD_ASSET = "ryeong/cards_eval1000.json"
        const val EXPECTED_DATASET_SHA256 =
            "f0feaebfdf5eb26c2a161a4b8c40d1307a6f5fa9c68f00309f05b69d03e7cd24"
        const val EXPECTED_CARDS = 1000
        const val TARGET_CARD_ID = "T001"
        const val CONTROL_CARD_ID = "S04979"
        const val INITIAL_QUERY = "블루오션컨설팅 AI 개발자"
        const val UPDATED_NAME = "검증손다은"
        const val UPDATED_COMPANY = "온디바이스검증회사"
        const val UPDATED_MEMO = "production cache invalidation semantic marker"
        const val UPDATED_QUERY = "검증손다은 온디바이스검증회사 AI 개발자"
    }
}
