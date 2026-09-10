package com.example.hjp.v4

import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A model boundary whose every reply is written down in advance.
 *
 * The real Gemma run surfaced a failure the local router cannot produce: after `get_contact`
 * returned, the model wrote a sentence about the contact instead of calling the tool the request
 * needed, and the turn was recorded as finished. Reproducing that needs a boundary that can be told
 * to end in prose at a chosen point — which is what this is.
 *
 * Everything below the boundary is the production path: the real registry, the real
 * `AgentWorkflowSession`, the real executor and the real `AgentKernel`. Only the model is scripted.
 */
class ScriptedModel(private val script: List<Step>) : AgentModelGateway {

    sealed interface Step {
        /** Ask for a tool. */
        data class Call(val tool: String, val arguments: JsonObject) : Step

        /** End the turn with text. The thing a model does when it decides it has said enough. */
        data class Prose(val text: String) : Step
    }

    /** What the boundary was actually asked, in order. Lets a test assert the repair happened. */
    val received = mutableListOf<String>()

    /** Steps the script never reached, so a test can tell "did not fire" from "fired and passed". */
    var consumed = 0
        private set

    private inner class Session : AgentModelSession {
        override val catalogRevision: String = "scripted"

        private fun next(): ModelDecision {
            if (consumed >= script.size) {
                return ModelDecision.FinalCandidate("스크립트가 끝났습니다.")
            }
            val step = script[consumed]
            consumed += 1
            return when (step) {
                is Step.Call -> ModelDecision.ToolCalls(
                    listOf(
                        ModelToolCall(
                            callId = "scripted-$consumed",
                            modelToolName = step.tool,
                            arguments = step.arguments,
                        ),
                    ),
                )
                is Step.Prose -> ModelDecision.FinalCandidate(step.text)
            }
        }

        override suspend fun decide(input: ModelInput): ModelDecision {
            received += "decide"
            return next()
        }

        override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision {
            received += "continue:${result.modelToolName}:${result.payload}"
            return next()
        }

        override fun streamFinal(input: FinalAnswerInput): Flow<String> = flowOf(input.draftText)

        override fun close() = Unit
    }

    override suspend fun openSession(config: ModelSessionConfig): AgentModelSession = Session()

    companion object {
        fun search(query: String) = Step.Call(
            "search_contacts",
            buildJsonObject {
                put("query", query)
                put("limit", 5)
            },
        )

        fun get(cardId: String, purpose: String = "email") = Step.Call(
            "get_contact",
            buildJsonObject {
                put("card_id", cardId)
                put("purpose", purpose)
            },
        )

        fun compose(to: String, channel: String = "email") = Step.Call(
            "open_compose",
            buildJsonObject {
                put("channel", channel)
                put("to", to)
                put("subject", "발주 문의")
                put("body", "수량 확인 부탁드립니다.")
            },
        )

        fun calendar(start: String, end: String, attendee: String? = null) = Step.Call(
            "create_calendar_event",
            buildJsonObject {
                put("title", "점검 일정")
                put("start_time", start)
                put("end_time", end)
                if (attendee != null) {
                    put("attendee_emails", buildJsonArray { add(JsonPrimitive(attendee)) })
                }
            },
        )

        fun update(cardId: String, memo: String) = Step.Call(
            "update_business_card",
            buildJsonObject {
                put("card_id", cardId)
                put("updates", buildJsonObject { put("memo", memo) })
            },
        )

        fun prose(text: String) = Step.Prose(text)
    }
}
