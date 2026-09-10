package com.example.hjp

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A local-only crash diagnostic. It deliberately has no dependency on AgentKernel, routing,
 * memory, tool execution, or Android tool surfaces. It recreates an SDK Conversation solely from
 * the captured native protocol and stops before injecting a tool result if Gemma diverges.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtStandaloneProtocolReplayInstrumentedTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun replayHld0054NativeProtocolWithoutAgent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        assumeFalse(AppContainer.isAndroidEmulator())
        val context = instrumentation.targetContext.applicationContext
        val sourceRoot = context.getExternalFilesDir(SOURCE_DIRECTORY)
            ?: error("external files directory unavailable")
        val configSource = File(sourceRoot, CONFIG_FILE)
        val protocolSource = File(sourceRoot, PROTOCOL_FILE)
        require(configSource.isFile && protocolSource.isFile) {
            "missing local replay source config=${configSource.isFile} protocol=${protocolSource.isFile}"
        }

        val runId = args.getString("a10ReplayRunId")?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }
            ?: "a10-litert014-directreplay-${System.currentTimeMillis()}"
        val runDirectory = File(requireNotNull(context.getExternalFilesDir(OUTPUT_DIRECTORY)), runId)
        check(!runDirectory.exists()) { "refusing to overwrite local diagnostic: ${runDirectory.absolutePath}" }
        check(runDirectory.mkdirs()) { "cannot create ${runDirectory.absolutePath}" }
        val telemetry = File(runDirectory, "replay_telemetry.local.jsonl.partial")
        val manifest = File(runDirectory, "manifest.local.json")

        val configOuter = json.parseToJsonElement(configSource.readText()).jsonObject
        val canonicalConfig = configOuter.getValue("canonical_config").jsonPrimitive.content
        val config = json.parseToJsonElement(canonicalConfig).jsonObject
        val messages = protocolSource.useLines { lines ->
            lines.filter(String::isNotBlank)
                .map { json.parseToJsonElement(it).jsonObject }
                .toList()
        }
        val begins = messages.filter { it.string("phase") == "send_begin" }
            .sortedBy { it.requiredInt("invocation_ordinal") }
        val returns = messages.filter { it.string("phase") == "send_return" }
            .associateBy { it.requiredInt("invocation_ordinal") }
        require(begins.isNotEmpty()) { "protocol has no sends" }
        require(begins.all { it.requiredInt("invocation_ordinal") in 1..begins.size }) {
            "protocol ordinals are not contiguous"
        }

        val modelFile = File(
            context.getExternalFilesDir("models") ?: File(context.filesDir, "models"),
            "hjp-agent.litertlm",
        )
        require(modelFile.isFile && modelFile.canRead()) { "Gemma artifact unavailable: ${modelFile.absolutePath}" }
        val cacheDirectory = File(context.cacheDir, "litert-standalone-replay-$runId")
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory)
        val declaredTools = config.getValue("tool_declarations").jsonArray.map { declaration ->
            tool(ReplayOpenApiTool(declaration.jsonObject))
        }
        val sampler = config.getValue("sampler").jsonObject
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(config.getValue("system_instruction").jsonPrimitive.content),
            samplerConfig = SamplerConfig(
                topK = sampler.getValue("top_k").jsonPrimitive.content.toInt(),
                topP = sampler.getValue("top_p").jsonPrimitive.content.toDouble(),
                temperature = sampler.getValue("temperature").jsonPrimitive.content.toDouble(),
            ),
            tools = declaredTools,
            automaticToolCalling = config.getValue("automatic_tool_calling").jsonPrimitive.boolean,
        )

        fun append(record: JsonObject) = telemetry.appendText(record.toString() + "\n")
        fun memory(): MemorySnapshot {
            val native = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val system = context.getSystemService(ActivityManager::class.java).let { manager ->
                ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            }
            return MemorySnapshot(native.nativePss, native.totalPss, system.availMem, system.lowMemory)
        }
        fun record(
            phase: String,
            replayOrdinal: Int,
            original: JsonObject,
            expected: JsonObject?,
            actual: DecisionSummary? = null,
            detail: String? = null,
        ) {
            val snapshot = memory()
            append(buildJsonObject {
                put("schema", "a10_litert_standalone_replay/v1")
                put("phase", phase); put("replay_ordinal", replayOrdinal)
                put("original_ordinal", original.requiredInt("invocation_ordinal"))
                put("turn", original.string("turn").orEmpty()); put("kind", original.string("kind").orEmpty())
                put("model_tool_name", original.string("model_tool_name").orEmpty())
                put("input_sha256", original.string("input_sha256").orEmpty())
                put("input_length", original.requiredLongOrZero("input_length"))
                put("expected_decision", expected?.decisionShape().orEmpty())
                put("actual_decision", actual?.shape.orEmpty())
                put("expected_tools", expected?.toolNamesJson() ?: JsonArray(emptyList()))
                put("actual_tools", actual?.toolNames ?: JsonArray(emptyList()))
                put("session_generation", 1); put("conversation_generation", 1)
                put("pid", Process.myPid()); put("native_pss_kb", snapshot.nativePssKb); put("total_pss_kb", snapshot.totalPssKb)
                put("available_memory_bytes", snapshot.availableMemoryBytes); put("low_memory", snapshot.lowMemory)
                detail?.let { put("detail", it) }
            })
        }

        var engine: Engine? = null
        var conversation: Conversation? = null
        var outcome = "INCOMPLETE"
        var completedOriginalOrdinal = 0
        try {
            append(buildJsonObject {
                put("schema", "a10_litert_standalone_replay/v1"); put("phase", "engine_begin")
                put("pid", Process.myPid()); put("backend", "CPU"); put("config_sha256", sha256(canonicalConfig))
                put("source_protocol_sha256", sha256(protocolSource.readBytes())); put("message_count", begins.size)
            })
            engine = Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = Backend.CPU(), cacheDir = cacheDirectory.absolutePath))
            engine.initialize()
            conversation = engine.createConversation(conversationConfig)
            for ((index, original) in begins.withIndex()) {
                val replayOrdinal = index + 1
                val expected = returns[original.requiredInt("invocation_ordinal")]?.get("model_decision")?.jsonObject
                record("send_begin", replayOrdinal, original, expected)
                val returned = when (original.string("kind")) {
                    "user", "workflow_note" -> conversation.sendMessage(original.getValue("raw_input").jsonPrimitive.content)
                    "tool_result" -> conversation.sendMessage(toolMessage(original))
                    else -> error("unsupported recorded native message kind=${original.string("kind")}")
                }
                val actual = returned.toSummary()
                record("send_return", replayOrdinal, original, expected, actual)
                completedOriginalOrdinal = original.requiredInt("invocation_ordinal")
                if (expected == null) {
                    outcome = "COMPLETED_CRASH_BOUNDARY_RETURNED"
                    break
                }
                if (!decisionsMatch(expected, actual)) {
                    outcome = "REPLAY_DIVERGED"
                    record("replay_diverged", replayOrdinal, original, expected, actual, "model decision does not match captured progression")
                    break
                }
            }
            if (outcome == "INCOMPLETE") outcome = "COMPLETED"
        } catch (error: Throwable) {
            outcome = "JAVA_EXCEPTION"
            append(buildJsonObject {
                put("schema", "a10_litert_standalone_replay/v1"); put("phase", "exception")
                put("exception_class", error::class.java.name); put("message", error.message.orEmpty())
                put("last_completed_original_ordinal", completedOriginalOrdinal); put("pid", Process.myPid())
            })
            throw error
        } finally {
            runCatching { conversation?.close() }
            runCatching { engine?.close() }
            val finalOutcome = outcome
            manifest.writeText(buildJsonObject {
                put("schema", "a10_litert_standalone_replay_manifest/v1"); put("run_id", runId)
                put("outcome", finalOutcome); put("last_completed_original_ordinal", completedOriginalOrdinal)
                put("config_source", configSource.name); put("protocol_source", protocolSource.name)
                put("production_agent_used", false); put("tool_execution_used", false); put("android_surface_used", false)
                put("backend", "CPU"); put("pid", Process.myPid())
            }.toString())
            Log.i(TAG, "REPLAY_FINISHED outcome=$finalOutcome last_original_ordinal=$completedOriginalOrdinal dir=${runDirectory.absolutePath}")
        }
    }

    private fun toolMessage(record: JsonObject): Message {
        val name = record.getValue("model_tool_name").jsonPrimitive.content
        val raw = record.getValue("raw_input").jsonPrimitive.content
        return Message.tool(Contents.of(listOf(Content.ToolResponse(name, json.parseToJsonElement(raw).toKotlinValue()))))
    }

    private fun Message.toSummary(): DecisionSummary = if (toolCalls.isNotEmpty()) {
        DecisionSummary("tool_calls", buildJsonArray { toolCalls.forEach { add(JsonPrimitive(it.name)) } })
    } else {
        val text = toString()
        DecisionSummary(if (text.isBlank()) "invalid" else "final", JsonArray(emptyList()))
    }

    private fun decisionsMatch(expected: JsonObject, actual: DecisionSummary): Boolean {
        val expectedShape = expected.decisionShape()
        if (expectedShape != actual.shape) return false
        return expectedShape != "tool_calls" || expected.toolNamesJson() == actual.toolNames
    }

    private fun JsonObject.decisionShape(): String = getValue("type").jsonPrimitive.content
    private fun JsonObject.toolNamesJson(): JsonArray = buildJsonArray {
        ((get("calls") as? JsonArray) ?: JsonArray(emptyList())).forEach { call -> add(JsonPrimitive(call.jsonObject.getValue("name").jsonPrimitive.content)) }
    }
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.requiredInt(key: String): Int = getValue(key).jsonPrimitive.content.toInt()
    private fun JsonObject.requiredLongOrZero(key: String): Long = string(key)?.toLongOrNull() ?: 0L

    private fun JsonElement.toKotlinValue(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> entries.associate { (key, value) -> key to value.toKotlinValue() }
        is JsonArray -> map { it.toKotlinValue() }
        is JsonPrimitive -> booleanOrNull ?: doubleOrNull ?: content
    }

    private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))
    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private data class DecisionSummary(val shape: String, val toolNames: JsonArray)
    private data class MemorySnapshot(val nativePssKb: Int, val totalPssKb: Int, val availableMemoryBytes: Long, val lowMemory: Boolean)

    private class ReplayOpenApiTool(private val declaration: JsonObject) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = buildJsonObject {
            put("name", declaration.getValue("model_name").jsonPrimitive.content)
            put("description", declaration.getValue("description").jsonPrimitive.content)
            put("parameters", declaration.getValue("input_schema").jsonObject)
        }.toString()
        override fun execute(paramsJsonString: String): String = "{\"ok\":false,\"error\":\"tool_execution_disabled_for_replay\"}"
    }

    private companion object {
        const val TAG = "HjpLiteRtStandaloneReplay"
        const val SOURCE_DIRECTORY = "litert-replay-source"
        const val OUTPUT_DIRECTORY = "litert-replay"
        const val CONFIG_FILE = "a10-litert014-fullprotocol-r1-20260908.conversation_config.local.json"
        const val PROTOCOL_FILE = "a10-litert014-t6history-r1-20260908.native_protocol.local.jsonl.partial"
    }
}
