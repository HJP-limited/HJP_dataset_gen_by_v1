package com.hjp.desktop

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
private data class EvalCase(
    val id: String,
    val category: String,
    val prompt: String,
    val expected_tools: List<String>,
    val expected_argument_contains: JsonObject = JsonObject(emptyMap()),
    val expected_final_contains: List<String> = emptyList(),
    val forbidden_final_terms: List<String> = emptyList(),
)

private data class CaseResult(
    val case: EvalCase,
    val actualTools: List<String>,
    val actualArguments: List<JsonObject>,
    val rawModelOutput: String,
    val toolResult: String,
    val finalAnswer: String,
    val toolExecutionSuccess: Boolean,
    val argumentAccurate: Boolean,
    val grounded: Boolean,
    val composeIntentDetected: Boolean,
    val recipientResolved: Boolean,
    val generatedDraft: Boolean,
    val generatedDraftRequested: Boolean,
    val draftJsonParsed: Boolean,
    val fallbackUsed: Boolean,
    val openComposeExecuted: Boolean,
    val recipientHallucinated: Boolean,
    val failureReasons: List<String>,
)

fun main(args: Array<String>) = runBlocking {
    val config = DesktopConfig.from(arrayOf("--mode", "model-agent", *args))
    val dataset = File(
        config.projectRoot,
        "desktop-agent-runner/src/modelEval/resources/gemma3_tool_eval.jsonl",
    )
    val cases = dataset.useLines { lines ->
        lines.filter(String::isNotBlank).map { EVAL_JSON.decodeFromString<EvalCase>(it) }.toList()
    }
    require(cases.size >= 50) { "모델 평가 case는 최소 50개여야 합니다: ${cases.size}" }

    val activeTrace = mutableListOf<Pair<String, String>>()
    val results = mutableListOf<CaseResult>()
    val knownRecipients = DesktopFileDataSource(config.cardDataFile).loadAll()
        .flatMap { listOf(it.email, it.phone, it.mobile) }
        .filter(String::isNotBlank)
        .toSet()
    val modelAgentRunner = DesktopAgentRunner(
        config = config,
        traceObserver = { stage, message -> activeTrace += stage to message },
        printOutput = false,
    )
    val composeRunner = DesktopAgentRunner(
        config = config.copy(mode = RunnerMode.FULL),
        traceObserver = { stage, message -> activeTrace += stage to message },
        printOutput = false,
    )
    try {
        modelAgentRunner.validateData()
        composeRunner.validateData()
        cases.forEachIndexed { index, case ->
            activeTrace.clear()
            val isCompose = case.category == "email" || case.category == "sms"
            val runner = if (isCompose) composeRunner else modelAgentRunner
            runner.resetSession()
            val execution = runCatching { runner.runPrompt(case.prompt) }
            val final = execution.getOrElse { "ERROR: ${it.message ?: it::class.java.simpleName}" }
            val parsedCalls = activeTrace.filter { it.first == "PARSED_TOOL_CALL" }
                .mapNotNull { (_, payload) -> runCatching { EVAL_JSON.parseToJsonElement(payload).jsonObject }.getOrNull() }
            val actualTools = parsedCalls.mapNotNull { it["name"]?.jsonPrimitive?.content }
            val actualArguments = parsedCalls.map {
                it["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            }
            val toolResults = activeTrace
                .filter { it.first == "TOOL_RESULT" && it.second != "not_executed" }
                .map(Pair<String, String>::second)
            val executionSuccess = if (case.expected_tools.isEmpty()) {
                toolResults.isEmpty()
            } else {
                actualTools == case.expected_tools &&
                    toolResults.size == case.expected_tools.size &&
                    toolResults.all(::toolResultSucceeded)
            }
            val argumentAccurate = case.expected_tools.isEmpty() || argumentContains(
                case.expected_argument_contains,
                actualArguments.firstOrNull(),
            )
            val generatedCall = activeTrace.lastOrNull { it.first == "GENERATED_TOOL_CALL" }
                ?.second
                ?.let { runCatching { EVAL_JSON.parseToJsonElement(it).jsonObject }.getOrNull() }
            val generatedArguments = generatedCall?.get("arguments") as? JsonObject
            val generatedRecipient = generatedArguments?.get("to")?.jsonPrimitive?.content.orEmpty()
            val directRecipient = directRecipient(case.prompt)
            val composeIntentDetected = !isCompose || activeTrace.any { it.first == "COMPOSE_INTENT" }
            val recipientResolved = !isCompose || (
                activeTrace.any { it.first == "RECIPIENT_RESOLUTION" && it.second.contains("status=resolved") } &&
                    generatedRecipient.isNotBlank() &&
                    if (directRecipient == null) generatedRecipient in knownRecipients
                    else directRecipient == generatedRecipient
                )
            val generatedDraftRequested = isCompose && activeTrace.any {
                it.first == "COMPOSE_INTENT" && it.second.contains("body_mode=generate")
            }
            val generatedDraft = !generatedDraftRequested || (
                generatedArguments?.get("body")?.jsonPrimitive?.content?.isNotBlank() == true &&
                    activeTrace.any { it.first == "DRAFT_VALIDATION" && it.second.contains("valid=true") }
                )
            val draftJsonParsed = !generatedDraftRequested || activeTrace.any {
                it.first == "DRAFT_VALIDATION" && it.second.contains("json_parsed=true")
            }
            val fallbackUsed = generatedDraftRequested && activeTrace.any {
                it.first == "DRAFT_VALIDATION" &&
                    (it.second.contains("fallback_used=true") || it.second.contains("fallback=true"))
            }
            val openComposeExecuted = !isCompose || (
                actualTools.lastOrNull() == "open_compose" &&
                    toolResults.lastOrNull()?.let(::toolResultSucceeded) == true
                )
            val recipientHallucinated = isCompose && generatedRecipient.isNotBlank() &&
                generatedRecipient != directRecipient && generatedRecipient !in knownRecipients
            val safetyBlocked = activeTrace.any {
                it.first == "GROUNDING_VALIDATION" && it.second.contains("safe_response=true")
            }
            val grounded = case.forbidden_final_terms.none(final::contains) && if (isCompose) {
                openComposeExecuted && !recipientHallucinated
            } else {
                case.expected_final_contains.all(final::contains) || safetyBlocked
            }
            val reasons = buildList {
                if (case.expected_tools != actualTools) {
                    add("tool_sequence_mismatch")
                }
                if (!argumentAccurate) add("argument_mismatch")
                if (!executionSuccess) add("tool_execution_failed")
                if (!grounded) add("grounding_or_final_content_mismatch")
                if (!composeIntentDetected) add("compose_intent_not_detected")
                if (!recipientResolved) add("recipient_resolution_failed")
                if (!generatedDraft) add("draft_generation_failed")
                if (!openComposeExecuted) add("open_compose_execution_failed")
                if (recipientHallucinated) add("recipient_hallucinated")
                execution.exceptionOrNull()?.let { add("exception:${it.message}") }
            }
            results += CaseResult(
                case = case,
                actualTools = actualTools,
                actualArguments = actualArguments,
                rawModelOutput = activeTrace.lastOrNull { it.first == "MODEL_RAW_OUTPUT" }?.second.orEmpty(),
                toolResult = toolResults.joinToString("\n"),
                finalAnswer = final,
                toolExecutionSuccess = executionSuccess,
                argumentAccurate = argumentAccurate,
                grounded = grounded,
                composeIntentDetected = composeIntentDetected,
                recipientResolved = recipientResolved,
                generatedDraft = generatedDraft,
                generatedDraftRequested = generatedDraftRequested,
                draftJsonParsed = draftJsonParsed,
                fallbackUsed = fallbackUsed,
                openComposeExecuted = openComposeExecuted,
                recipientHallucinated = recipientHallucinated,
                failureReasons = reasons,
            )
            println("[${index + 1}/${cases.size}] ${case.id}: ${if (reasons.isEmpty()) "PASS" else reasons.joinToString()}")
        }
    } finally {
        composeRunner.close()
        modelAgentRunner.close()
    }

    val nativeResults = results.filterNot { it.case.category == "email" || it.case.category == "sms" }
    val composeResults = results - nativeResults.toSet()
    val expectedToolCases = nativeResults.filter { it.case.expected_tools.isNotEmpty() }
    val noToolCases = nativeResults.filter { it.case.expected_tools.isEmpty() }
    val selectionCorrect = nativeResults.count {
        it.case.expected_tools.firstOrNull() == it.actualTools.firstOrNull()
    }
    val falsePositiveNoTool = noToolCases.count { it.actualTools.isNotEmpty() }
    val hallucinated = nativeResults.count {
        it.case.category == "nonexistent_contact" &&
            it.case.forbidden_final_terms.any(it.finalAnswer::contains)
    }
    val generatedDraftCases = composeResults.filter(CaseResult::generatedDraftRequested)
    val metrics = linkedMapOf(
        "tool_call_parse_rate" to rate(expectedToolCases.count { it.actualTools.isNotEmpty() }, expectedToolCases.size),
        "tool_selection_accuracy" to rate(selectionCorrect, nativeResults.size),
        "argument_accuracy" to rate(expectedToolCases.count(CaseResult::argumentAccurate), expectedToolCases.size),
        "tool_sequence_accuracy" to rate(
            nativeResults.count { it.case.expected_tools == it.actualTools },
            nativeResults.size,
        ),
        "tool_execution_success_rate" to rate(
            expectedToolCases.count(CaseResult::toolExecutionSuccess),
            expectedToolCases.size,
        ),
        "no_tool_false_positive_rate" to rate(falsePositiveNoTool, noToolCases.size),
        "grounded_answer_rate" to rate(nativeResults.count(CaseResult::grounded), nativeResults.size),
        "hallucinated_contact_count" to hallucinated.toDouble(),
        "compose_intent_accuracy" to rate(
            composeResults.count(CaseResult::composeIntentDetected),
            composeResults.size,
        ),
        "recipient_resolution_accuracy" to rate(
            composeResults.count(CaseResult::recipientResolved),
            composeResults.size,
        ),
        "draft_generation_success_rate" to rate(
            generatedDraftCases.count(CaseResult::generatedDraft),
            generatedDraftCases.size,
        ),
        "draft_json_parse_rate" to rate(
            generatedDraftCases.count(CaseResult::draftJsonParsed),
            generatedDraftCases.size,
        ),
        "open_compose_execution_rate" to rate(
            composeResults.count(CaseResult::openComposeExecuted),
            composeResults.size,
        ),
        "recipient_hallucination_count" to composeResults.count(CaseResult::recipientHallucinated).toDouble(),
        "fallback_rate" to rate(
            generatedDraftCases.count(CaseResult::fallbackUsed),
            generatedDraftCases.size,
        ),
    )

    val reportsRoot = File(config.projectRoot, "desktop-agent-runner/build/reports/model-eval")
    val reportDir = File(reportsRoot, config.modelId)
    check(reportDir.mkdirs() || reportDir.isDirectory)
    File(reportDir, "result.json").writeText(buildJsonObject {
        put("model_id", config.modelId)
        put("model", config.modelFile.absolutePath)
        put("model_file_size_bytes", config.modelFile.length())
        put("backend", config.backend)
        put("case_count", results.size)
        put("metrics", buildJsonObject {
            metrics.forEach { (name, value) -> put(name, value) }
        })
        put("failures", buildJsonArray {
            results.filter { it.failureReasons.isNotEmpty() }.forEach { result ->
                add(buildJsonObject {
                    put("id", result.case.id)
                    put("category", result.case.category)
                    put("prompt", result.case.prompt)
                    put("expected_tools", JsonArray(result.case.expected_tools.map(::JsonPrimitive)))
                    put("actual_tools", JsonArray(result.actualTools.map(::JsonPrimitive)))
                    put("actual_arguments", JsonArray(result.actualArguments))
                    put("raw_model_output", result.rawModelOutput)
                    put("tool_result", result.toolResult)
                    put("final_answer", result.finalAnswer)
                    put("failure_reason", result.failureReasons.joinToString(";"))
                })
            }
        })
    }.toString())
    File(reportDir, "result.csv").writeText(buildString {
        appendLine("id,category,expected_tools,actual_tools,argument_accurate,execution_success,grounded,failure_reason")
        results.forEach { result ->
            appendLine(listOf(
                result.case.id,
                result.case.category,
                result.case.expected_tools.joinToString(">"),
                result.actualTools.joinToString(">"),
                result.argumentAccurate,
                result.toolExecutionSuccess,
                result.grounded,
                result.failureReasons.joinToString(";"),
            ).joinToString(",") { csv(it.toString()) })
        }
    })
    writeComparisonReports(reportsRoot)

    println("model-agent evaluation complete: ${results.size} cases")
    metrics.forEach { (name, value) -> println("$name=$value") }
    println("reports=${reportDir.absolutePath}")
}

private fun writeComparisonReports(reportsRoot: File) {
    val reports = reportsRoot.listFiles()
        .orEmpty()
        .filter(File::isDirectory)
        .mapNotNull { directory ->
            val file = File(directory, "result.json")
            runCatching { EVAL_JSON.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
        }
        .sortedBy { it["model_id"]?.jsonPrimitive?.content.orEmpty() }
    if (reports.isEmpty()) return
    val metricNames = reports.flatMap { report ->
        (report["metrics"] as? JsonObject)?.keys.orEmpty()
    }.distinct().sorted()
    File(reportsRoot, "model-comparison.json").writeText(buildJsonObject {
        put("models", buildJsonArray {
            reports.forEach { report ->
                add(buildJsonObject {
                    put("model_id", report["model_id"] ?: JsonPrimitive(""))
                    put("model", report["model"] ?: JsonPrimitive(""))
                    put("model_file_size_bytes", report["model_file_size_bytes"] ?: JsonPrimitive(0))
                    put("metrics", report["metrics"] ?: JsonObject(emptyMap()))
                })
            }
        })
    }.toString())
    File(reportsRoot, "model-comparison.csv").writeText(buildString {
        appendLine(
            (listOf("model_id", "model", "model_file_size_bytes") + metricNames)
                .joinToString(",") { csv(it) },
        )
        reports.forEach { report ->
            val metrics = report["metrics"] as? JsonObject ?: JsonObject(emptyMap())
            val values = listOf(
                report["model_id"]?.jsonPrimitive?.content.orEmpty(),
                report["model"]?.jsonPrimitive?.content.orEmpty(),
                report["model_file_size_bytes"]?.jsonPrimitive?.content.orEmpty(),
            ) + metricNames.map { metrics[it]?.jsonPrimitive?.content.orEmpty() }
            appendLine(values.joinToString(",") { csv(it) })
        }
    })
}

private fun argumentContains(expected: JsonObject, actual: JsonObject?): Boolean {
    if (expected.isEmpty()) return actual != null
    if (actual == null) return false
    return expected.all { (key, expectedValue) ->
        val actualValue = actual[key] ?: return@all false
        when {
            expectedValue is JsonPrimitive && actualValue is JsonPrimitive ->
                actualValue.content.contains(expectedValue.content, ignoreCase = true)
            else -> actualValue == expectedValue
        }
    }
}

private fun toolResultSucceeded(payload: String): Boolean {
    val root = runCatching { EVAL_JSON.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return false
    val result = (root["result"] as? JsonObject) ?: root
    return result["ok"]?.jsonPrimitive?.content == "true"
}

private fun directRecipient(prompt: String): String? =
    EMAIL.find(prompt)?.value ?: PHONE.find(prompt)?.value

private fun rate(numerator: Int, denominator: Int): Double =
    if (denominator == 0) 1.0 else numerator.toDouble() / denominator

private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private val EVAL_JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }
private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
private val PHONE = Regex("""(?<!\d)01[016789][-\s]?\d{3,4}[-\s]?\d{4}(?!\d)""")
