package com.hjp.desktop

import com.hjp.agent.litert.common.LiteRtNativeAgentModelGateway
import com.hjp.agent.litert.common.LiteRtNativeBackend
import com.hjp.tool.android.MessageChannel
import com.hjp.tool.android.MessageDraftRequest
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object ModelPerformanceMain {
@JvmStatic
fun main(args: Array<String>) = runBlocking {
    val config = DesktopConfig.from(arrayOf("--mode", "model-agent", *args))
    val traces = mutableListOf<Pair<String, String>>()
    val sampler = PeakRssSampler()
    val gateway = LiteRtNativeAgentModelGateway(
        modelId = config.modelId,
        modelFile = config.modelFile,
        cacheDirectory = config.cacheDirectory,
        backend = config.backend.toNativeBackend(),
        strictContactGrounding = true,
        trace = { stage, message -> traces += stage to message },
    )
    var totalResponseMillis = -1L
    var draftWallMillis = -1L
    var draftJsonParsed = false
    var draftFallback = false
    var draftBody = ""
    sampler.use {
        DesktopAgentRunner(
            config = config,
            modelAgentGatewayOverride = gateway,
            traceObserver = { stage, message -> traces += stage to message },
            printOutput = false,
        ).use { runner ->
            runner.validateData()
            val requestStarted = System.nanoTime()
            runner.runPrompt("김지원 명함을 찾아줘.")
            totalResponseMillis = elapsedMillis(requestStarted)

            val draftStarted = System.nanoTime()
            val draft = gateway.generate(MessageDraftRequest(
                channel = MessageChannel.EMAIL,
                recipientName = null,
                purpose = "지난번 미팅에 대한 감사",
                userContext = "test@example.com한테 지난번 미팅에 대한 감사 내용을 담아서 메일 작성해줘.",
            ))
            draftWallMillis = elapsedMillis(draftStarted)
            draftJsonParsed = draft.jsonParsed
            draftFallback = draft.usedFallback
            draftBody = draft.draft.body
        }
    }

    val engineInitialization = traceMillis(traces, "engine_initialization_ms")
    val firstRequest = traceMillis(traces, "request_latency_ms")
    val nativeCall = traces.firstOrNull { it.first == "PARSED_TOOL_CALL" && it.second.contains(config.modelId) }
    val toolExecuted = nativeCall != null && traces.any {
        it.first == "TOOL_RESULT" && it.second != "not_executed"
    }
    val report = buildJsonObject {
        put("model_id", config.modelId)
        put("model_path", config.modelFile.absolutePath)
        put("model_file_size_bytes", config.modelFile.length())
        put("backend", config.backend)
        put("engine_initialization_ms", engineInitialization)
        put("first_request_latency_ms", firstRequest)
        put("total_response_latency_ms", totalResponseMillis)
        put("peak_process_rss_kb", sampler.peakRssKb())
        put("peak_memory_scope", "current JVM process RSS sampled with ps; GPU memory and subprocess RSS excluded")
        put("native_tool_call_success", toolExecuted)
        put("draft_generation_ms", draftWallMillis)
        put("draft_json_parsed", draftJsonParsed)
        put("draft_fallback_used", draftFallback)
        put("draft_body", draftBody)
    }
    val root = File(config.projectRoot, "desktop-agent-runner/build/reports/model-performance")
    check(root.mkdirs() || root.isDirectory)
    File(root, "${config.modelId}.json").writeText(report.toString())
    writePerformanceComparison(root)
    println(report)
}
}

private class PeakRssSampler : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val peakKb = AtomicLong(0L)
    private val samplerThread = thread(start = true, isDaemon = true, name = "peak-rss-sampler") {
        val pid = ProcessHandle.current().pid().toString()
        while (running.get()) {
            runCatching {
                val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid).start()
                process.outputStream.close()
                val value = process.inputStream.bufferedReader().use { it.readText() }
                    .trim().toLongOrNull() ?: 0L
                process.waitFor(1, TimeUnit.SECONDS)
                peakKb.accumulateAndGet(value, ::maxOf)
            }
            Thread.sleep(100)
        }
    }

    fun peakRssKb(): Long = peakKb.get()

    override fun close() {
        running.set(false)
        samplerThread.join(2_000)
    }
}

private fun traceMillis(traces: List<Pair<String, String>>, key: String): Long =
    traces.asSequence()
        .filter { it.first == "MODEL_PERFORMANCE" }
        .mapNotNull { (_, value) -> Regex("""$key=(\d+)""").find(value)?.groupValues?.get(1)?.toLongOrNull() }
        .firstOrNull() ?: -1L

private fun writePerformanceComparison(root: File) {
    val reports = root.listFiles { file -> file.extension == "json" && file.name != "model-comparison.json" }
        .orEmpty()
        .mapNotNull { file -> runCatching { PERFORMANCE_JSON.parseToJsonElement(file.readText()).jsonObject }.getOrNull() }
        .sortedBy { it["model_id"]?.jsonPrimitive?.content.orEmpty() }
    File(root, "model-comparison.json").writeText(buildJsonObject {
        put("models", buildJsonArray { reports.forEach(::add) })
    }.toString())
    File(root, "model-comparison.csv").writeText(buildString {
        appendLine(
            "model_id,model_file_size_bytes,backend,engine_initialization_ms,first_request_latency_ms," +
                "total_response_latency_ms,peak_process_rss_kb,native_tool_call_success,draft_generation_ms," +
                "draft_json_parsed,draft_fallback_used",
        )
        reports.forEach { report ->
            val keys = listOf(
                "model_id", "model_file_size_bytes", "backend", "engine_initialization_ms",
                "first_request_latency_ms", "total_response_latency_ms", "peak_process_rss_kb",
                "native_tool_call_success", "draft_generation_ms", "draft_json_parsed",
                "draft_fallback_used",
            )
            appendLine(keys.joinToString(",") { key ->
                "\"${report[key]?.jsonPrimitive?.content.orEmpty().replace("\"", "\"\"")}\""
            })
        }
    })
}

private fun String.toNativeBackend(): LiteRtNativeBackend = when (this) {
    "cpu" -> LiteRtNativeBackend.CPU
    "gpu" -> LiteRtNativeBackend.GPU
    "npu" -> LiteRtNativeBackend.NPU
    else -> error("지원하지 않는 backend입니다: $this")
}

private fun elapsedMillis(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000L

private val PERFORMANCE_JSON = Json { ignoreUnknownKeys = true }
