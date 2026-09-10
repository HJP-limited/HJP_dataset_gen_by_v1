package com.example.hjp.evalmt

import com.example.hjp.MultiturnScenarioHarness
import com.example.hjp.eval.ryeong.RyeongCards
import com.example.hjp.eval.ryeong2.EvidenceRoot
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.RyeongContactSearchBackend
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Replays the 400-scenario tool-calling evaluation set through the production agent.
 *
 * On-policy and end-to-end: one fresh session per scenario, every turn fed to the real kernel, and
 * whatever the agent did on turn *n* is what turn *n+1* sees. No gold tool result is injected, no
 * intermediate state is restored, and the search runs through the production
 * `RyeongContactSearchBackend` over the real 1,000-card fixture.
 *
 * What this runner does *not* do is score anything. It records what happened; the metrics are
 * computed afterwards from this record, so the scorer can be re-run without re-running the agent.
 *
 * Opt-in, so `./gradlew test` never executes it by accident.
 */
class MultiturnToolCallEvalRunner {

    private val enabled =
        (System.getenv("HJP_MULTITURN_TOOLCALL_EVAL")
            ?: System.getProperty("hjpMultiturnToolCallEval")) == "true"

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `replay the multiturn tool-call evaluation set`() {
        assumeTrue("multiturn tool-call evaluation not requested", enabled)

        val setPath = System.getenv("HJP_EVAL_SET")
            ?: "tools/agent_eval_multiturn_v1/data/eval_set_v1.json"
        val outPath = System.getenv("HJP_EVAL_OUT")
            ?: "tools/agent_eval_multiturn_v1/results/raw_turns.jsonl"
        val setFile = EvidenceRoot.file(setPath)
        check(setFile.isFile) { "evaluation set missing: $setPath" }
        val root = json.parseToJsonElement(setFile.readText(Charsets.UTF_8)).jsonObject
        val scenarios = root["scenarios"]!!.jsonArray

        val cards = RyeongCards.load()
        check(cards.size == RyeongCards.EXPECTED_COUNT) { "unexpected card count ${cards.size}" }

        val out = EvidenceRoot.file(outPath)
        out.parentFile?.mkdirs()
        check(!out.exists()) { "result file already exists: $outPath" }

        val startedAt = System.currentTimeMillis()
        var turnTotal = 0
        var callTotal = 0
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.use { sink ->
            scenarios.forEach { element ->
                val scenario = element.jsonObject
                val id = scenario["scenario_id"]!!.jsonPrimitive.content
                // A fresh harness per scenario is a fresh session: nothing carries over.
                val harness = MultiturnScenarioHarness(
                    cards = cards,
                    searchBackendFactory = { repository ->
                        RyeongContactSearchBackend(
                            repository = repository,
                            embeddingEngineFactory = { OnDeviceEmbeddingEngine.production() },
                        )
                    },
                )
                val turnRecords = buildJsonArray {
                    runBlocking {
                        scenario["turns"]!!.jsonArray.forEach { turnElement ->
                            val spec = turnElement.jsonObject
                            val text = spec["user"]!!.jsonPrimitive.content
                            val record = harness.turn(text)
                            turnTotal += 1
                            callTotal += record.executedTools.size
                            add(buildJsonObject {
                                put("index", spec["index"]!!.jsonPrimitive.content.toInt())
                                put("user", text)
                                putJsonArray("executed_tools") {
                                    record.executedTools.forEach { add(JsonPrimitive(it)) }
                                }
                                putJsonArray("tool_arguments") {
                                    record.toolArguments.forEach { (name, args) ->
                                        add(buildJsonObject {
                                            put("tool", name)
                                            put("arguments", args)
                                        })
                                    }
                                }
                                put("answer", record.answer)
                                put("is_error", record.isError)
                                put("act", record.act.name)
                                put("outcome_type", record.outcomeType?.name ?: "")
                                put("selected_card_id",
                                    record.memory.selectedContact?.cardId ?: "")
                                putJsonArray("candidate_card_ids") {
                                    record.memory.candidateContacts.forEach {
                                        add(JsonPrimitive(it.cardId))
                                    }
                                }
                                putJsonArray("search_rankings") {
                                    record.searchRankings.forEach { ranking ->
                                        add(buildJsonArray { ranking.forEach { add(JsonPrimitive(it)) } })
                                    }
                                }
                                putJsonArray("retrieval_modes") {
                                    record.retrievalModes.forEach { add(JsonPrimitive(it)) }
                                }
                                putJsonArray("new_compose_drafts") {
                                    record.newComposeDrafts.forEach {
                                        add(buildJsonObject {
                                            put("channel", it.channel.name)
                                            put("to", it.to)
                                            put("subject", it.subject ?: "")
                                            put("body", it.body)
                                        })
                                    }
                                }
                                putJsonArray("new_calendar_drafts") {
                                    record.newCalendarDrafts.forEach {
                                        add(buildJsonObject {
                                            put("title", it.title)
                                            put("start_millis", it.startMillis)
                                            put("end_millis", it.endMillis)
                                            put("location", it.location ?: "")
                                            put("description", it.description ?: "")
                                        })
                                    }
                                }
                            })
                        }
                    }
                }
                val finalCard = runBlocking {
                    harness.session().conversationMemory.selectedContact?.cardId ?: ""
                }
                val mutated = harness.repository.cards
                    .filter { card -> cards.firstOrNull { it.id == card.id } != card }
                    .map { it.id }
                sink.write(buildJsonObject {
                    put("scenario_id", id)
                    put("split", scenario["split"]!!.jsonPrimitive.content)
                    put("category", scenario["category"]!!.jsonPrimitive.content)
                    put("workflow", scenario["workflow"]!!.jsonPrimitive.content)
                    put("turn_count", scenario["turn_count"]!!.jsonPrimitive.content.toInt())
                    put("final_selected_card_id", finalCard)
                    put("compose_draft_count", harness.messages.drafts.size)
                    put("calendar_draft_count", harness.calendar.drafts.size)
                    putJsonArray("mutated_cards") {
                        mutated.forEach { add(JsonPrimitive(it)) }
                    }
                    putJsonArray("mutated_card_titles") {
                        harness.repository.cards.filter { it.id in mutated }.forEach {
                            add(buildJsonObject { put("card_id", it.id); put("title", it.title)
                                put("company", it.company); put("memo", it.memo) })
                        }
                    }
                    put("turns", turnRecords)
                }.toString())
                sink.write("\n")
                harness.close()
            }
        }
        val elapsed = System.currentTimeMillis() - startedAt
        println(
            "multiturn tool-call replay: scenarios=${scenarios.size} turns=$turnTotal " +
                "tool_calls=$callTotal elapsed_ms=$elapsed out=$outPath",
        )
    }
}
