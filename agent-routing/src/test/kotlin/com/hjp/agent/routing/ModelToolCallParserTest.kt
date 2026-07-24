package com.hjp.agent.routing

import com.hjp.agent.contract.ModelDecision
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelToolCallParserTest {
    @Test
    fun `parses fenced tool call json`() {
        val decision = ModelToolCallParser.parseDecision(
            """```json
                {"type":"tool_call","name":"search_contacts","arguments":{"query":"김지원","limit":5}}
                ```""".trimIndent(),
            allowPlainTextFinal = false,
        ) as ModelDecision.ToolCalls

        assertEquals("search_contacts", decision.calls.single().modelToolName)
        assertEquals("김지원", (decision.calls.single().arguments["query"] as JsonPrimitive).content)
    }

    @Test
    fun `rejects missing or non object arguments`() {
        val missing = ModelToolCallParser.parseDecision(
            """{"type":"tool_call","name":"search_contacts"}""",
            allowPlainTextFinal = false,
        )
        val wrong = ModelToolCallParser.parseDecision(
            """{"type":"tool_call","name":"search_contacts","arguments":"bad"}""",
            allowPlainTextFinal = false,
        )

        assertTrue(missing is ModelDecision.Invalid)
        assertTrue(wrong is ModelDecision.Invalid)
    }

    @Test
    fun `plain model answer remains a final candidate`() {
        val decision = ModelToolCallParser.parseDecision("안녕하세요")
        assertEquals("안녕하세요", (decision as ModelDecision.FinalCandidate).draftText)
    }
}
