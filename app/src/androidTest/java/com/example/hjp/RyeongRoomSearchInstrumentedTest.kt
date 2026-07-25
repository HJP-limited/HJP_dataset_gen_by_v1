package com.example.hjp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.RyeongContactSearchBackend
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RyeongRoomSearchInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "ryeong-search-instrumented.db"
    private lateinit var database: HjpDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun roomCrudRefreshesSearchAndPersistsAcrossRestart() = runBlocking {
        val store = RoomBusinessCardRepository(context, database.businessCardDao())
        val backend = RyeongContactSearchBackend(store)
        val fixture = BusinessCardRecord(
            id = "INSTRUMENTED-001",
            name = "테스트사용자",
            company = "초기회사",
            title = "검증담당",
            department = "품질팀",
            industry = "test",
            location = "서울",
        )

        store.upsert(fixture)
        assertEquals(fixture.id, backend.search("초기회사", 5).single().cardId)

        store.update(fixture.id, mapOf("company" to "수정회사"), emptySet(), "2026-07-25T00:00:00Z")
        assertTrue(backend.search("초기회사", 5).isEmpty())
        assertEquals(fixture.id, backend.search("수정회사", 5).single().cardId)

        database.close()
        database = openDatabase()
        val restartedStore = RoomBusinessCardRepository(context, database.businessCardDao())
        val restartedBackend = RyeongContactSearchBackend(restartedStore)
        assertEquals("수정회사", restartedBackend.get(fixture.id)?.company)
        assertEquals(fixture.id, restartedBackend.search("수정회사", 5).single().cardId)

        assertTrue(restartedStore.delete(fixture.id))
        assertNull(restartedBackend.get(fixture.id))
        assertTrue(restartedBackend.search("테스트사용자", 5).isEmpty())
    }

    @Test
    fun currentVersionOneSchemaSeedsWithoutDestructiveMigration() = runBlocking {
        val store = RoomBusinessCardRepository(context, database.businessCardDao())
        val seeded = store.loadAll()

        assertFalse(seeded.isEmpty())
        assertTrue(seeded.all { it.id.isNotBlank() && it.name.isNotBlank() })
        assertEquals(1, database.openHelper.readableDatabase.version)
    }

    private fun openDatabase(): HjpDatabase =
        Room.databaseBuilder(context, HjpDatabase::class.java, databaseName).build()
}
