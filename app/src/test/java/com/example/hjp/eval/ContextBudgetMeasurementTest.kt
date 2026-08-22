package com.example.hjp.eval

import com.hjp.agent.core.CalibratedGemmaTokenEstimator
import com.hjp.agent.core.ContextBudget
import com.hjp.agent.core.ContextPreflight
import com.hjp.agent.core.ContextPreflightResult
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.ModelDeploymentResolver
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.GetContactPlugin
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.CatalogContext
import com.hjp.tool.datetime.GetCurrentDateTimePlugin
import com.example.hjp.MultiturnScenarioHarness
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * What one request costs before any conversation exists.
 *
 * The catalog is the dominant fixed cost, and it is the term the budget model was most wrong about,
 * so it is measured from the real six-tool registry rather than assumed.
 */
class ContextBudgetMeasurementTest {
    @Test
    fun `record the fixed prompt cost of the production tool catalog`() = runBlocking {
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
                "session", "ko-KR", emptySet(),
                setOf(
                    "android.external_ui", "contact.local_search", "contact.local_update",
                    "datetime.current",
                ),
            ),
        )
        val catalogText = ContextPreflight.toolCatalogText(snapshot.contractsByModelName.values)
        val catalogTokens = CalibratedGemmaTokenEstimator.estimate(catalogText)
        val systemTokens = CalibratedGemmaTokenEstimator.estimate(SYSTEM_INSTRUCTION)

        val budget = ContextBudget(maxPromptTokens = 3_072)
        val fixedPlusReserves = systemTokens + catalogTokens + ContextPreflight.MIN_USER_INPUT_TOKENS +
            budget.reservedForResponseTokens + budget.safetyMarginTokens
        val withNextTurnReserve = fixedPlusReserves + budget.nextTurnReserveTokens

        val deployment = ModelDeploymentResolver.resolve(java.io.File("../models/gemma-4-E2B-it.litertlm"))
        val preflight = ContextPreflight.check(
            ModelDeploymentResolver.KNOWN_ARTIFACTS.first().let { known ->
                deployment.copy(
                    artifactId = known.artifactId,
                    appContextLimitTokens = known.appContextLimitTokens,
                    budget = ModelDeploymentResolver.budgetFor(known.appContextLimitTokens),
                )
            },
            SYSTEM_INSTRUCTION,
            catalogText,
        )

        EvalReport.write(
            "context_budget.json",
            """
            {
              "measurement_type": ${EvalReport.q("app-side calibrated estimate of the native input; NOT a LiteRT tokenizer reading")},
              "tokenizer_identity": ${EvalReport.q("CalibratedGemmaTokenEstimator")},
              "tool_count": ${snapshot.contractsByModelName.size},
              "tool_catalog_chars": ${catalogText.length},
              "tool_catalog_tokens": $catalogTokens,
              "system_instruction_chars": ${SYSTEM_INSTRUCTION.length},
              "system_instruction_tokens": $systemTokens,
              "min_user_input_tokens": ${ContextPreflight.MIN_USER_INPUT_TOKENS},
              "output_reserve_tokens": ${budget.reservedForResponseTokens},
              "safety_margin_tokens": ${budget.safetyMarginTokens},
              "next_request_reserve_tokens": ${budget.nextTurnReserveTokens},
              "assumed_tool_catalog_reserve_tokens": ${budget.reservedForToolCatalogTokens},
              "preflight_required_tokens": $fixedPlusReserves,
              "required_including_next_request_reserve": $withNextTurnReserve,
              "budget_tokens": ${budget.maxPromptTokens},
              "preflight_result": ${EvalReport.q(preflight::class.simpleName ?: "unknown")},
              "preflight_reason": ${EvalReport.q(
                  (preflight as? ContextPreflightResult.Failure)?.reasonKo ?: "",
              )}
            }
            """.trimIndent() + "\n",
        )
        harness.close()
    }

    private companion object {
        /** The production composition root's own instruction, not a copy of it. */
        val SYSTEM_INSTRUCTION = com.example.hjp.AppContainer.SYSTEM_INSTRUCTION
    }
}
