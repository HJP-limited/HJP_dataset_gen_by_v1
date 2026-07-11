package com.hjp.tool.contact

import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.contract.ToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPluginsTest {
    @Test
    fun `search result omits phone and email while get returns detail`() = runBlocking {
        val card = BusinessCardRecord("C001", "김지원", company = "비전글로벌", title = "대표",
            industry = "finance", location = "서울", phone = "010-0000", email = "test@example.com", tags = listOf("투자"))
        val repository = object : BusinessCardRepository {
            override suspend fun loadAll() = listOf(card)
            override suspend fun getById(cardId: String) = card.takeIf { it.id == cardId }
        }
        val backend = RyeongContactSearchBackend(repository)
        val context = ToolExecutionContext("s", "t", "Asia/Seoul")
        val search = SearchContactsPlugin(backend).execute(
            ToolRequest("1", ContactToolContracts.Search.capabilityId, ContactToolContracts.Search.version,
                buildJsonObject { put("query", "투자 대표") }), context,
        ) as ToolExecutionResult.Success

        assertFalse(search.data.toString().contains("010-0000"))
        assertFalse(search.data.toString().contains("test@example.com"))

        val get = GetContactPlugin(backend).execute(
            ToolRequest("2", ContactToolContracts.Get.capabilityId, ContactToolContracts.Get.version,
                buildJsonObject { put("card_id", "C001") }), context,
        ) as ToolExecutionResult.Success
        assertTrue(get.data.toString().contains("test@example.com"))
    }

    @Test
    fun `update business card mutates repository and returns before and after`() = runBlocking {
        var card = BusinessCardRecord("C001", "김지원", company = "비전글로벌", memo = "old")
        val repository = object : MutableBusinessCardRepository {
            override suspend fun loadAll() = listOf(card)
            override suspend fun getById(cardId: String) = card.takeIf { it.id == cardId }
            override suspend fun update(
                cardId: String,
                updates: Map<String, String>,
                clearFields: Set<String>,
                updatedAt: String,
            ): BusinessCardUpdateResult? {
                val before = card.takeIf { it.id == cardId } ?: return null
                val after = before.copy(
                    memo = updates["memo"] ?: before.memo,
                    phone = if ("phone" in clearFields) "" else before.phone,
                    updatedAt = updatedAt,
                )
                card = after
                return BusinessCardUpdateResult(before, after)
            }
        }

        val result = UpdateBusinessCardPlugin(repository, clockMillis = { 0L }).execute(
            ToolRequest(
                "3",
                ContactToolContracts.Update.capabilityId,
                ContactToolContracts.Update.version,
                buildJsonObject {
                    put("card_id", "C001")
                    put("updates", buildJsonObject { put("memo", "VIP") })
                    put("clear_fields", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("phone"))
                    })
                },
            ),
            ToolExecutionContext("s", "t", "Asia/Seoul"),
        ) as ToolExecutionResult.Success

        assertTrue(result.data.toString().contains("\"before\""))
        assertTrue(result.data.toString().contains("\"after\""))
        assertEquals("VIP", card.memo)
        assertEquals("", card.phone)
        assertEquals("1970-01-01T00:00:00Z", card.updatedAt)
    }
}
