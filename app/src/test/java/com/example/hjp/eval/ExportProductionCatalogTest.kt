package com.example.hjp.eval

import com.example.hjp.AppContainer
import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.GetContactPlugin
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.CatalogContext
import com.hjp.tool.datetime.GetCurrentDateTimePlugin
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Exports the production system instruction and tool contracts.
 *
 * The desktop Gemma run reads this file instead of a hand-written copy, so what the model is asked
 * is the same catalog the app registers. A copied prompt would drift from the real one exactly when
 * the comparison mattered.
 */
class ExportProductionCatalogTest {
    @Test
    fun `export the production system instruction and tool catalog`() = runBlocking {
        val harness = MultiturnScenarioHarness()
        val registry = DefaultToolRegistry(
            listOf(
                SearchContactsPlugin(harness.backend),
                GetContactPlugin(harness.backend),
                UpdateBusinessCardPlugin(harness.repository),
                CreateCalendarEventPlugin(harness.calendar),
                OpenComposePlugin(harness.messages),
                GetCurrentDateTimePlugin(),
            ).map { ToolImplementationCandidate(it) },
        )
        val snapshot = registry.snapshot(
            CatalogContext(
                "export", "ko-KR", emptySet(),
                setOf(
                    "android.external_ui", "contact.local_search", "contact.local_update",
                    "datetime.current",
                ),
            ),
        )
        val tools = snapshot.contractsByModelName.values.sortedBy { it.modelName }
        val body = buildString {
            append("{\n")
            append("  \"system_instruction\": ").append(EvalReport.q(AppContainer.SYSTEM_INSTRUCTION))
            append(",\n  \"tools\": [\n")
            append(tools.joinToString(",\n") { contract ->
                "    {\"name\": ${EvalReport.q(contract.modelName)}, " +
                    "\"description\": ${EvalReport.q(contract.description)}, " +
                    "\"parameters\": ${contract.inputSchema}}"
            })
            append("\n  ]\n}\n")
        }
        EvalReport.write("production_tool_catalog.json", body)
        harness.close()
    }
}
