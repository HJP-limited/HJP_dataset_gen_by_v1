package com.hjp.desktop

import com.hjp.tool.contact.RyeongContactSearchBackend
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopFileDataSourceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `loads the Android app real business card asset`() = runBlocking {
        val source = DesktopFileDataSource(realAsset())
        val cards = source.loadAll()

        assertFalse(cards.isEmpty())
        assertTrue(cards.all { it.id.isNotBlank() && it.name.isNotBlank() })
        assertEquals(cards.first(), source.getById(cards.first().id))
    }

    @Test
    fun `missing asset error contains the exact path`() = runBlocking {
        val missing = File(temporaryFolder.root, "missing business_cards.json")
        val error = runCatching { DesktopFileDataSource(missing).loadAll() }.exceptionOrNull()
        assertEquals("명함 asset 파일을 찾을 수 없습니다: ${missing.absolutePath}", error?.message)
    }

    @Test
    fun `search cases use only records from the loaded data`() = runBlocking {
        val source = DesktopFileDataSource(realAsset())
        val cards = source.loadAll()
        val backend = RyeongContactSearchBackend(source)
        val existing = cards.first()
        val missing = generateSequence("존재하지않는이름") { "${it}X" }.first { candidate ->
            cards.none { it.name == candidate }
        }

        assertEquals(existing.id, backend.search(existing.name, 5).first().card.id)
        assertTrue(backend.search(missing, 5).isEmpty())
        if (existing.company.isNotBlank()) assertTrue(backend.search(existing.company, 5).any { it.card.id == existing.id })
        val semanticQuery = cards.firstOrNull { it.title.contains("엔지니어") }?.let { "AI 개발자" } ?: existing.title
        assertTrue(backend.search(semanticQuery, 5).isNotEmpty())
        assertTrue(backend.search("!@#$%^&*()", 5).isEmpty())
    }

    @Test
    fun `same-name fixture returns both real fixture records`() = runBlocking {
        val file = temporaryFolder.newFile("business_cards.json")
        file.writeText(
            """[
              {"id":"A1","name":"홍길동","company":"첫회사","title":"개발자"},
              {"id":"A2","name":"홍길동","company":"둘회사","title":"대표"}
            ]""".trimIndent(),
        )
        val hits = RyeongContactSearchBackend(DesktopFileDataSource(file)).search("홍길동", 5)

        assertEquals(setOf("A1", "A2"), hits.map { it.card.id }.toSet())
    }

    @Test
    fun `updates stay in memory and do not rewrite Android asset`() = runBlocking {
        val file = temporaryFolder.newFile("cards.json")
        val original = """[{"id":"A1","name":"홍길동","company":"전회사"}]"""
        file.writeText(original)
        val source = DesktopFileDataSource(file)

        val result = source.update("A1", mapOf("company" to "새회사"), emptySet(), "now")

        assertEquals("새회사", result?.after?.company)
        assertEquals(original, file.readText())
    }

    private fun realAsset(): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, "app/src/main/assets/cards/business_cards.json")
            if (candidate.isFile) return candidate
            current = current.parentFile
        }
        error("real business card asset not found")
    }
}
