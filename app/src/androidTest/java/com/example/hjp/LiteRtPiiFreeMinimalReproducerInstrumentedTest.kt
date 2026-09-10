package com.example.hjp

import android.app.ActivityManager
import android.os.Build
import android.os.Debug
import android.os.Process
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
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PII-free standalone reduction for the LiteRT Message.tool continuation crash.  Every prompt and
 * tool payload below is synthetic.  It never creates an Agent or executes a tool/surface.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtPiiFreeMinimalReproducerInstrumentedTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun replaySyntheticToolContinuation() {
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        assumeFalse(AppContainer.isAndroidEmulator())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val args = InstrumentationRegistry.getArguments()
        val experiment = args.getString("a10MinimalExperiment") ?: "synthetic_full"
        require(experiment in setOf("synthetic_full", "synthetic_direct_get", "protocol_redacted", "redacted_no_prior_user", "redacted_current_turn_only", "fixture_only")) { "unknown experiment=$experiment" }
        val runId = args.getString("a10MinimalRunId")?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }
            ?: "a10-litert014-piifree-$experiment-${System.currentTimeMillis()}"
        val sourceRoot = context.getExternalFilesDir(SOURCE_DIRECTORY) ?: error("external files unavailable")
        val configSource = File(sourceRoot, CONFIG_FILE)
        require(configSource.isFile) { "missing local config" }
        val configOuter = json.parseToJsonElement(configSource.readText()).jsonObject
        val canonicalConfig = configOuter.getValue("canonical_config").jsonPrimitive.content
        val config = json.parseToJsonElement(canonicalConfig).jsonObject
        val protocolSource = File(sourceRoot, PROTOCOL_FILE)
        val output = File(requireNotNull(context.getExternalFilesDir(OUTPUT_DIRECTORY)), runId)
        check(!output.exists() && output.mkdirs()) { "cannot create diagnostic output" }
        val telemetry = File(output, "pii_free_telemetry.local.jsonl.partial")
        telemetry.appendText("{\"schema\":\"a10_litert_pii_free_reproducer/v1\",\"phase\":\"test_begin\",\"pid\":${Process.myPid()}}\n")
        val cache = File(context.cacheDir, "litert-piifree-$runId").also { check(it.mkdirs() || it.isDirectory) }
        val model = File(context.getExternalFilesDir("models") ?: context.filesDir, "hjp-agent.litertlm")
        require(model.isFile) { "missing Gemma artifact" }
        val sampler = config.getValue("sampler").jsonObject
        val tools = config.getValue("tool_declarations").jsonArray.map { tool(SyntheticOpenApiTool(it.jsonObject)) }
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(config.getValue("system_instruction").jsonPrimitive.content),
            samplerConfig = SamplerConfig(
                topK = sampler.getValue("top_k").jsonPrimitive.content.toInt(),
                topP = sampler.getValue("top_p").jsonPrimitive.content.toDouble(),
                temperature = sampler.getValue("temperature").jsonPrimitive.content.toDouble(),
            ),
            tools = tools,
            automaticToolCalling = config.getValue("automatic_tool_calling").jsonPrimitive.boolean,
        )
        fun telemetry(phase: String, step: Int, expectedTool: String?, actual: Message? = null, payload: JsonElement? = null) {
            val debug = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val system = context.getSystemService(ActivityManager::class.java).let { manager ->
                ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            }
            telemetry.appendText(buildJsonObject {
                put("schema", "a10_litert_pii_free_reproducer/v1"); put("experiment", experiment); put("phase", phase); put("step", step)
                put("expected_tool", expectedTool.orEmpty()); put("actual_tools", actual?.toolCalls?.let { calls -> buildJsonArray { calls.forEach { add(JsonPrimitive(it.name)) } } } ?: JsonArray(emptyList()))
                put("payload_sha256", payload?.let { sha256(it.toString()) }.orEmpty()); put("payload_length", payload?.toString()?.length ?: 0)
                put("native_pss_kb", debug.nativePss); put("total_pss_kb", debug.totalPss); put("available_memory_bytes", system.availMem)
                put("pid", Process.myPid()); put("config_sha256", sha256(canonicalConfig)); put("all_data_synthetic", true)
            }.toString() + "\n")
        }
        var step = 0
        fun requireTool(message: Message, expected: String): Message {
            telemetry("model_return", step, expected, message)
            check(message.toolCalls.map { it.name } == listOf(expected)) {
                "REPLAY_DIVERGED expected=$expected actual=${message.toolCalls.map { it.name }}"
            }
            return message
        }

        var engine: Engine? = null
        var conversation: Conversation? = null
        try {
            engine = Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU(), cacheDir = cache.absolutePath))
            engine.initialize()
            conversation = engine.createConversation(conversationConfig)
            telemetry.appendText("{\"schema\":\"a10_litert_pii_free_reproducer/v1\",\"phase\":\"conversation_open\",\"pid\":${Process.myPid()}}\n")
            if (experiment == "fixture_only") {
                val fixture = File(sourceRoot, SHARED_FIXTURE_FILE)
                require(fixture.isFile) { "missing synthetic fixture" }
                val messages = json.parseToJsonElement(fixture.readText()).jsonObject.getValue("messages").jsonArray
                for (entry in messages) {
                    val message = entry.jsonObject
                    step = message.getValue("ordinal").jsonPrimitive.content.toInt()
                    val kind = message.getValue("kind").jsonPrimitive.content
                    val expected = when (step) { 1 -> "search_contacts"; 4 -> "get_contact"; else -> null }
                    val payload = message["payload"]
                    telemetry("send_begin", step, expected, payload = payload)
                    val result = when (kind) {
                        "user" -> conversation.sendMessage(message.getValue("input").jsonPrimitive.content)
                        "tool_result" -> conversation.sendMessage(toolMessage(message.getValue("model_tool_name").jsonPrimitive.content, requireNotNull(payload)))
                        else -> error("unsupported fixture kind=$kind")
                    }
                    if (expected != null) requireTool(result, expected) else telemetry("model_return", step, null, result)
                }
                telemetry("completed", step, null)
                telemetry.appendText("{\"schema\":\"a10_litert_pii_free_reproducer/v1\",\"phase\":\"test_method_return\",\"pid\":${Process.myPid()}}\n")
                return
            }
            if (experiment == "protocol_redacted" || experiment == "redacted_no_prior_user" || experiment == "redacted_current_turn_only") {
                require(protocolSource.isFile) { "missing local protocol" }
                val source = protocolSource.useLines { lines -> lines.filter(String::isNotBlank).map { json.parseToJsonElement(it).jsonObject }.toList() }
                val begins = source.filter { it.string("phase") == "send_begin" }.associateBy { it.getValue("invocation_ordinal").jsonPrimitive.content.toInt() }
                val synthetic = ProtocolRedactor(json, begins.getValue(1), begins.getValue(2), begins.getValue(5))
                // Exact native message order from the proven replay, with contact scalar values only
                // replaced before any message crosses the LiteRT API boundary.
                val replayOrdinals = when (experiment) {
                    "redacted_no_prior_user" -> listOf(1, 2, 4, 5)
                    // The preceding search request/result is removed as one protocol exchange: an
                    // orphan Message.tool would be invalid and is intentionally never injected.
                    "redacted_current_turn_only" -> listOf(4, 5)
                    else -> (1..5).toList()
                }
                // This fixture contains only transformed strings/payloads and is suitable for
                // sharing with LiteRT maintainers; the source protocol is never copied.
                File(output, "synthetic_protocol_fixture.json").writeText(buildJsonObject {
                    put("all_data_synthetic", true); put("replay_ordinals", buildJsonArray { replayOrdinals.forEach { add(JsonPrimitive(it)) } })
                    put("messages", buildJsonArray {
                        replayOrdinals.forEach { ordinal ->
                            val record = begins.getValue(ordinal)
                            add(buildJsonObject {
                                put("ordinal", ordinal); put("kind", record.getValue("kind").jsonPrimitive.content)
                                put("model_tool_name", record.string("model_tool_name").orEmpty())
                                if (record.getValue("kind").jsonPrimitive.content == "tool_result") put("payload", synthetic.payload(ordinal))
                                else put("input", synthetic.text(record.getValue("raw_input").jsonPrimitive.content))
                            })
                        }
                    })
                }.toString())
                for (ordinal in replayOrdinals) {
                    val record = begins.getValue(ordinal)
                    step = ordinal
                    val expected = when (ordinal) { 1 -> "search_contacts"; 4 -> "get_contact"; else -> null }
                    val kind = record.getValue("kind").jsonPrimitive.content
                    val raw = record.getValue("raw_input").jsonPrimitive.content
                    telemetry("send_begin", step, expected, payload = if (kind == "tool_result") synthetic.payload(ordinal) else null)
                    val result = when (kind) {
                        "user" -> conversation.sendMessage(synthetic.text(raw))
                        "tool_result" -> conversation.sendMessage(toolMessage(record.getValue("model_tool_name").jsonPrimitive.content, synthetic.payload(ordinal)))
                        else -> error("unsupported protocol kind=$kind")
                    }
                    if (expected != null) requireTool(result, expected) else telemetry("model_return", step, null, result)
                }
                telemetry("completed", step, null)
                Log.i(TAG, "PII_FREE_REDACTED_PROTOCOL_COMPLETED dir=${output.absolutePath}")
                telemetry.appendText("{\"schema\":\"a10_litert_pii_free_reproducer/v1\",\"phase\":\"test_method_return\",\"pid\":${Process.myPid()}}\n")
                return
            }
            if (experiment == "synthetic_full") {
                step = 1
                telemetry("send_begin", step, "search_contacts")
                requireTool(conversation.sendMessage(SYNTHETIC_SEARCH_USER), "search_contacts")
                step = 2
                telemetry("send_begin", step, null, payload = SYNTHETIC_SEARCH_RESULT)
                conversation.sendMessage(toolMessage("search_contacts", SYNTHETIC_SEARCH_RESULT)).also { telemetry("model_return", step, null, it) }
            }
            step += 1
            telemetry("send_begin", step, "get_contact")
            val contactRequest = if (experiment == "synthetic_full") SYNTHETIC_FOLLOW_UP else SYNTHETIC_DIRECT_GET_USER
            requireTool(conversation.sendMessage(contactRequest), "get_contact")
            step += 1
            telemetry("send_begin", step, null, payload = SYNTHETIC_CONTACT_RESULT)
            // This is the target native continuation boundary. No tool implementation is invoked.
            conversation.sendMessage(toolMessage("get_contact", SYNTHETIC_CONTACT_RESULT)).also { telemetry("model_return", step, null, it) }
            telemetry("completed", step, null)
            telemetry.appendText("{\"schema\":\"a10_litert_pii_free_reproducer/v1\",\"phase\":\"test_method_return\",\"pid\":${Process.myPid()}}\n")
            Log.i(TAG, "PII_FREE_COMPLETED experiment=$experiment dir=${output.absolutePath}")
        } finally {
            runCatching { conversation?.close() }
            runCatching { engine?.close() }
        }
    }

    private fun toolMessage(name: String, payload: JsonElement): Message =
        Message.tool(Contents.of(listOf(Content.ToolResponse(name, payload.toKotlinValue()))))

    private fun JsonElement.toKotlinValue(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> entries.associate { (key, value) -> key to value.toKotlinValue() }
        is JsonArray -> map { it.toKotlinValue() }
        is JsonPrimitive -> booleanOrNull ?: doubleOrNull ?: content
    }

    private class SyntheticOpenApiTool(private val declaration: JsonObject) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = buildJsonObject {
            put("name", declaration.getValue("model_name").jsonPrimitive.content)
            put("description", declaration.getValue("description").jsonPrimitive.content)
            put("parameters", declaration.getValue("input_schema").jsonObject)
        }.toString()
        override fun execute(paramsJsonString: String): String = "{\"ok\":false,\"error\":\"disabled\"}"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "HjpLiteRtPiiFree"
        const val SOURCE_DIRECTORY = "litert-replay-source"
        const val OUTPUT_DIRECTORY = "litert-pii-free"
        const val CONFIG_FILE = "a10-litert014-fullprotocol-r1-20260908.conversation_config.local.json"
        const val PROTOCOL_FILE = "a10-litert014-t6history-r1-20260908.native_protocol.local.jsonl.partial"
        const val SHARED_FIXTURE_FILE = "synthetic_protocol_fixture.json"
        const val SYNTHETIC_SEARCH_USER = "가상 담당자를 찾아줘."
        const val SYNTHETIC_FOLLOW_UP = "그 사람의 상세 연락처를 보여줘."
        const val SYNTHETIC_DIRECT_GET_USER = "synthetic-card-001의 상세 연락처를 보여줘."
        val SYNTHETIC_SEARCH_RESULT = buildJsonObject {
            put("ok", true); put("capability", "search_contacts"); put("contract_version", "v1")
            put("data", buildJsonObject {
                put("results", buildJsonArray { add(buildJsonObject {
                    put("card_id", "synthetic-card-001"); put("name", "가상 담당자"); put("company", "Synthetic Company")
                    put("title", "Contact"); put("match_summary", "synthetic exact match"); put("score", 1.0)
                }) }); put("count", 1); put("mode", "HYBRID"); put("fallback_used", false); put("engine", "synthetic")
            })
        }
        val SYNTHETIC_CONTACT_RESULT = buildJsonObject {
            put("ok", true); put("capability", "get_contact"); put("contract_version", "v1")
            put("data", buildJsonObject {
                put("card_id", "synthetic-card-001"); put("name", "가상 담당자"); put("name_en", "Synthetic Contact")
                put("company", "Synthetic Company"); put("title", "Contact"); put("department", "Synthetic Department")
                put("industry", "Synthetic Industry"); put("location", "Synthetic City"); put("phone", "+0-000-0000")
                put("mobile", "+0-000-0001"); put("email", "contact@example.test"); put("address", "Synthetic Address")
                put("website", "https://example.test"); put("memo", "Synthetic record"); put("tags", JsonArray(emptyList())); put("updated_at", "2026-01-01T00:00:00Z")
            })
        }
    }

    /** Local-only source transformation: contact data values are replaced, never logged. */
    private class ProtocolRedactor(
        private val json: Json,
        userRecord: JsonObject,
        searchRecord: JsonObject,
        contactRecord: JsonObject,
    ) {
        private val substitutions = linkedMapOf<String, String>()
        private val payloads: Map<Int, JsonElement>

        init {
            // The initial compact search phrase can contain a name which is not necessarily a
            // value returned by the search payload. Replace it before rendering any history.
            Regex("([가-힣]{2,4})(?=씨\\s*찾아줘)").find(
                userRecord.getValue("raw_input").jsonPrimitive.content,
            )?.groupValues?.getOrNull(1)?.let { substitutions[it] = "synthetic_query_person" }
            val search = json.parseToJsonElement(searchRecord.getValue("raw_input").jsonPrimitive.content).jsonObject
            val contact = json.parseToJsonElement(contactRecord.getValue("raw_input").jsonPrimitive.content).jsonObject
            val syntheticSearch = replaceData(search, "search")
            val syntheticContact = replaceData(contact, "contact")
            payloads = mapOf(2 to syntheticSearch, 5 to syntheticContact)
        }

        fun text(value: String): String = substitutions.entries.sortedByDescending { it.key.length }
            .fold(value) { current, (real, replacement) -> if (real.isEmpty()) current else current.replace(real, replacement) }
        fun payload(ordinal: Int): JsonElement = payloads.getValue(ordinal)

        private fun replaceData(root: JsonObject, family: String): JsonElement = buildJsonObject {
            root.forEach { (key, value) ->
                put(key, if (key == "data") replace(value, "$family.data") else value)
            }
        }

        private fun replace(value: JsonElement, path: String): JsonElement = when (value) {
            is JsonObject -> buildJsonObject { value.forEach { (key, nested) -> put(key, replace(nested, "$path.$key")) } }
            is JsonArray -> buildJsonArray { value.forEachIndexed { index, nested -> add(replace(nested, "$path.$index")) } }
            is JsonPrimitive -> if (value.isString) {
                val real = value.content
                val synthetic = "synthetic_${path.substringAfterLast('.').replace(Regex("[^A-Za-z0-9_]"), "_")}_${substitutions.size + 1}"
                substitutions.putIfAbsent(real, synthetic)
                JsonPrimitive(substitutions.getValue(real))
            } else value
            else -> value
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
