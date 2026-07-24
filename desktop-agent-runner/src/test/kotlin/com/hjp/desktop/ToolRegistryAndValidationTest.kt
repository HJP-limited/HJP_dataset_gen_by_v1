package com.hjp.desktop

import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.core.DefaultToolExecutor
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contract.CatalogContext
import com.hjp.tool.contract.StandardToolErrorCodes
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ToolRegistryAndValidationTest {
    @Test
    fun `registry selects search plugin and unknown name is rejected`() = runBlocking {
        val registry = registry()
        val snapshot = registry.snapshot(CatalogContext("s", "ko-KR"))

        assertNotNull(snapshot.bindingFor("search_contacts"))
        val result = DefaultToolExecutor(registry).execute(
            ModelToolCall("unknown-1", "does_not_exist", buildJsonObject { }),
            snapshot,
            ToolExecutionContext("s", "t", "ko-KR", "Asia/Seoul"),
        ) as ToolExecutionResult.Failure

        assertEquals(StandardToolErrorCodes.TOOL_NOT_AVAILABLE, result.error.code)
    }

    @Test
    fun `missing empty and wrong argument types are rejected`() = runBlocking {
        val registry = registry()
        val snapshot = registry.snapshot(CatalogContext("s", "ko-KR"))
        val executor = DefaultToolExecutor(registry)
        val context = ToolExecutionContext("s", "t", "ko-KR", "Asia/Seoul")

        val missing = executor.execute(ModelToolCall("missing", "search_contacts", buildJsonObject { }), snapshot, context)
            as ToolExecutionResult.Failure
        val empty = executor.execute(ModelToolCall("empty", "search_contacts", buildJsonObject { put("query", "") }), snapshot, context)
            as ToolExecutionResult.Failure
        val wrong = executor.execute(ModelToolCall("wrong", "search_contacts", buildJsonObject { put("query", JsonPrimitive(123)) }), snapshot, context)
            as ToolExecutionResult.Failure

        assertEquals(StandardToolErrorCodes.INVALID_ARGUMENTS, missing.error.code)
        assertEquals(StandardToolErrorCodes.INVALID_ARGUMENTS, empty.error.code)
        assertEquals(StandardToolErrorCodes.INVALID_ARGUMENTS, wrong.error.code)
    }

    private fun registry(): DefaultToolRegistry {
        val source = DesktopFileDataSource(realAsset())
        val plugin = SearchContactsPlugin(RyeongContactSearchBackend(source))
        return DefaultToolRegistry(listOf(ToolImplementationCandidate(plugin)))
    }

    private fun realAsset(): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            File(current, "app/src/main/assets/cards/business_cards.json").takeIf(File::isFile)?.let { return it }
            current = current.parentFile
        }
        error("real business card asset not found")
    }
}
