package com.example.hjp

import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.example.hjp.search.AndroidEmbeddingGemmaEngine
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.AgentRuntimeCounters
import com.hjp.agent.contract.CountingAgentModelGateway
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.agent.core.AgentKernel
import com.hjp.agent.core.AgentRuntimeEnvironment
import com.hjp.agent.core.AgentSession
import com.hjp.agent.core.AgentSessionManager
import com.hjp.agent.core.AgentTurnPolicy
import com.hjp.agent.core.ArtifactVerificationPolicy
import com.hjp.agent.core.CachingArtifactDigestProvider
import com.hjp.agent.core.ContextPreflight
import com.hjp.agent.core.ConversationHistoryStrategy
import com.hjp.agent.core.DefaultToolExecutor
import com.hjp.agent.core.DefaultToolObservationMapper
import com.hjp.agent.core.DefaultToolPolicyEngine
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.InMemoryAgentSessionStore
import com.hjp.agent.core.ModelContextSelector
import com.hjp.agent.core.ModelDeploymentResolver
import com.hjp.agent.core.ToolExecutor
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.agent.litert.LiteRtAgentModelGateway
import com.hjp.agent.litert.LiteRtBackendPreference
import com.hjp.searchlookup.EmbeddingEngine
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.android.AndroidCalendarComposerBackend
import com.hjp.tool.android.AndroidMessageComposerBackend
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.GetContactPlugin
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contact.SearchDiagnostics
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.ConfirmationGateway
import com.hjp.tool.contract.ToolCatalogSnapshot
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import com.hjp.tool.datetime.GetCurrentDateTimePlugin
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A-8 device implementation of the unchanged JVM 400-scenario replay contract.
 *
 * It emits the same `hjp_multiturn_toolcall_eval_raw/v1` fields consumed by
 * tools/agent_eval_multiturn_v1/score.py.  Extra `a8_*` fields are evidence only and are ignored
 * by that scorer.  Unlike the JVM runner it uses native LiteRT Gemma, EmbeddingGemma, Room and the
 * Android Intent plugins.  It never selects an external app or presses Send/Save.
 */
@RunWith(AndroidJUnit4::class)
class MultiturnToolCallDeviceEvalInstrumentedTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun replayJvm400ScenarioGoldOnPhysicalProductionStack() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("a8Mode") ?: "disabled"
        // Crash-isolation only: preserve the production tool and inference sequence while
        // independently toggling the runner's post-open Resolver dismissal.
        val dismissExternalSurfaces = args.getString("a8DismissExternalSurfaces")
            ?.toBooleanStrictOrNull() ?: true
        // A later protocol-only run can reuse an already captured config artifact without writing
        // a second copy. This controls runner evidence only, never Conversation construction.
        val captureConversationConfig = args.getString("a8CaptureConversationConfig")
            ?.toBooleanStrictOrNull() ?: true
        // Evaluation-only, PII-minimized state observability. Disabled by default and never read
        // by Agent decision code.
        val structuredTelemetryEnabled = args.getString("a17Telemetry")
            ?.toBooleanStrictOrNull() ?: false
        val backendPreference = when (args.getString("a8Backend")?.lowercase()) {
            null, "cpu" -> LiteRtBackendPreference.CPU_ONLY
            "gpu" -> LiteRtBackendPreference.GPU_THEN_CPU
            else -> error("a8Backend must be cpu or gpu")
        }
        if (mode == "disabled") {
            Log.i(TAG, "skipped: use -e a8Mode representative, subset, or full")
            return@runBlocking
        }
        require(mode == "representative" || mode == "subset" || mode == "full") {
            "unknown a8Mode=$mode"
        }
        val requestedScenarioIds = args.getString("a8ScenarioIds")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.takeIf { it.isNotEmpty() }
            .orEmpty()
        require(mode != "subset" || requestedScenarioIds.isNotEmpty()) {
            "a8Mode=subset requires a non-empty comma-separated a8ScenarioIds argument"
        }
        require(requestedScenarioIds.size == requestedScenarioIds.toSet().size) {
            "a8ScenarioIds contains duplicate scenario ids"
        }
        // Test-runner-only lifecycle probe.  It repeats selected scenarios within one process;
        // production Agent state is still reset exactly as it is between normal scenarios.
        val lifecycleRepeats = args.getString("a8LifecycleRepeats")?.toIntOrNull() ?: 1
        val resetBetweenScenarios = args.getString("a8ResetBetweenScenarios")?.toBooleanStrictOrNull() ?: true
        require(lifecycleRepeats in 1..2) { "a8LifecycleRepeats must be 1 or 2" }
        require(lifecycleRepeats == 1 || (mode == "subset" && requestedScenarioIds.size == 1)) {
            "lifecycle repeats require exactly one subset scenario"
        }
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        assumeFalse(AppContainer.isAndroidEmulator())

        val context = instrumentation.targetContext.applicationContext
        val evalBytes = instrumentation.context.assets.open(EVAL_ASSET).use { it.readBytes() }
        assertEquals(EVAL_SHA256, sha256(evalBytes))
        val evalRoot = json.parseToJsonElement(String(evalBytes, Charsets.UTF_8)).jsonObject
        val allScenarios = evalRoot.getValue("scenarios").jsonArray
        assertEquals(EXPECTED_SCENARIOS, allScenarios.size)
        assertEquals(EXPECTED_TURNS, allScenarios.sumOf { it.jsonObject.getValue("turn_count").jsonPrimitive.content.toInt() })

        val selected = when (mode) {
            "full" -> allScenarios
            "representative" -> {
                val wanted = REPRESENTATIVE_IDS.toSet()
                allScenarios.filter { it.jsonObject.getValue("scenario_id").jsonPrimitive.content in wanted }
            }
            "subset" -> {
                val wanted = requestedScenarioIds.toSet()
                allScenarios.filter { it.jsonObject.getValue("scenario_id").jsonPrimitive.content in wanted }
            }
            else -> error("validated above")
        }
        // Non-metric state-machine validation variant: replay REG-0067's correction/search
        // prefix, then exercise explicit ordinal selection, pronoun reference, and calendar
        // resume. It is runner-only evidence and is never scored as an E-2 scenario.
        val validationVariant = args.getString("a8ValidationVariant")
        val selectedForRun = if (validationVariant == "reg0067_selection") {
            val base = selected.singleOrNull { it.jsonObject.getValue("scenario_id").jsonPrimitive.content == "REG-0067" }
                ?: error("reg0067_selection requires REG-0067")
            val turns = base.jsonObject.getValue("turns").jsonArray.take(4)
            val extraUsers = listOf(
                "두 번째 사람",
                "그 사람 연락처 알려줘",
                "내일 오전 10시에 미팅 잡아줘",
            )
            buildJsonObject {
                base.jsonObject.forEach { (key, value) -> put(key, value) }
                put("scenario_id", "REG-0067-NON_METRIC-SELECTION")
                put("turn_count", turns.size + extraUsers.size)
                putJsonArray("turns") {
                    turns.forEach { add(it) }
                    extraUsers.forEachIndexed { offset, user ->
                        add(buildJsonObject {
                            put("index", turns.size + offset + 1)
                            put("user", user)
                            put("decision_kind", "NO_TOOL")
                            putJsonArray("expected_calls") { }
                            putJsonArray("allowed_tool_sequences") { add(buildJsonArray { }) }
                            putJsonArray("forbidden_tools") { }
                        })
                    }
                }
            }.let(::listOf)
        } else selected
        val expectedSelected = when (mode) {
            "full" -> EXPECTED_SCENARIOS
            "representative" -> REPRESENTATIVE_IDS.size
            "subset" -> requestedScenarioIds.size
            else -> error("validated above")
        }
        assertEquals("representative scenarios missing", expectedSelected, selected.size)
        val lifecycleSelected = selectedForRun.flatMap { scenario ->
            (1..lifecycleRepeats).map { lifecycleRun -> scenario to lifecycleRun }
        }

        val cardBytes = instrumentation.context.assets.open(CARD_ASSET).use { it.readBytes() }
        assertEquals(FROZEN_CARDS_SHA256, sha256(cardBytes))
        val frozenCards = parseCards(String(cardBytes, Charsets.UTF_8))
        assertEquals(EXPECTED_CARDS, frozenCards.size)

        val modelRoot = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val modelFile = File(modelRoot, "hjp-agent.litertlm")
        val deployment = ModelDeploymentResolver.resolve(
            modelFile,
            digestProvider = CachingArtifactDigestProvider(),
            verificationPolicy = ArtifactVerificationPolicy.REQUIRE_OFFICIAL_GENERATIVE_ARTIFACT,
        )
        assertTrue("official generative artifact unavailable: ${deployment.diagnosticSummary()}", deployment.usable)
        assertEquals(ModelDeploymentResolver.OFFICIAL_GENERATIVE_ARTIFACT_SHA256, deployment.verifiedSha256)

        val database = HjpDatabase.getInstance(context)
        val dao = database.businessCardDao()
        restoreFrozenRows(dao, frozenCards)
        assertEquals(EXPECTED_CARDS, dao.count())
        val repository = RoomBusinessCardRepository(context, dao, json)
        val nativeEmbedding = AndroidEmbeddingGemmaEngine(context)
        assertTrue(nativeEmbedding.diagnosticStatus(), nativeEmbedding.isModelBacked)
        val embedding = CountingEmbeddingEngine(OnDeviceEmbeddingEngine.production(nativeEmbedding))
        val retrieval = mutableListOf<SearchDiagnostics>()
        val backend = RyeongContactSearchBackend(
            repository = repository,
            embeddingEngineFactory = { embedding },
            diagnostics = { diagnostic -> retrieval += diagnostic },
        )

        // Exactly one startup warm-up. Existing persisted vectors are reused; a missing/stale index
        // is allowed to build once here, never once per scenario.
        val warmup = backend.search(WARMUP_QUERY, 5)
        assertEquals("HYBRID", warmup.mode)
        assertFalse(warmup.fallbackUsed)
        // A focused subset may contain only deterministic exact-name reads and therefore have no
        // tool-turn retrieval diagnostics. The startup warm-up is the production retrieval
        // prerequisite; do not falsely block such a subset on hybridTurns == 0.
        val hybridRetrievalVerified = warmup.mode == "HYBRID" && !warmup.fallbackUsed
        assertEquals(EXPECTED_CARDS, repository.loadEmbeddings(embedding.name()).size)
        val fullIndexDocumentCalls = embedding.documentCalls

        val contactDirectory = RepositoryContactDirectory(repository)
        val plugins = listOf(
            SearchContactsPlugin(backend),
            GetContactPlugin(backend),
            UpdateBusinessCardPlugin(repository, onUpdated = {
                backend.invalidate()
                contactDirectory.invalidate()
            }),
            CreateCalendarEventPlugin(AndroidCalendarComposerBackend(context)),
            OpenComposePlugin(AndroidMessageComposerBackend(context)),
            GetCurrentDateTimePlugin(),
        )
        val registry = DefaultToolRegistry(plugins.map(::ToolImplementationCandidate))
        val counters = AgentRuntimeCounters()
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val observationLock = Any()
        var observationSink: ((JsonObject) -> Unit)? = null
        var protocolSink: ((JsonObject) -> Unit)? = null
        var configSink: ((JsonObject) -> Unit)? = null
        var currentSurfaceState = "none"
        var observedSessionGeneration = -1L
        var activeSessionStore: InMemoryAgentSessionStore? = null
        var activeObservingGateway: ObservingGateway? = null
        lateinit var surfaceExecutor: SurfaceTracingExecutor
        data class ScenarioRuntime(val sessionStore: InMemoryAgentSessionStore, val gateway: ObservingGateway, val manager: AgentSessionManager, val kernel: AgentKernel)
        fun createScenarioRuntime(): ScenarioRuntime {
            val store = InMemoryAgentSessionStore()
            val gateway = ObservingGateway(
                CountingAgentModelGateway(
                    LiteRtAgentModelGateway(
                        modelFile,
                        File(context.cacheDir, "litertlm-a8-400"),
                        backendPreference,
                        counters = counters,
                    ),
                    counters,
                ),
                observation = { phase, invocation, kind, scenario, turn, lifecycleRun, promptOrPayload, surfaceState, toolName, redactedPayload ->
                val processMemory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                val systemMemory = android.app.ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
                val record = buildJsonObject {
                    put("phase", phase); put("invocation_ordinal", invocation); put("kind", kind)
                    put("scenario_id", scenario); put("turn", turn); put("lifecycle_run", lifecycleRun)
                    put("prompt_or_payload_sha256", sha256(promptOrPayload.toByteArray()))
                    put("pid", Process.myPid()); put("native_pss_kb", processMemory.nativePss); put("total_pss_kb", processMemory.totalPss)
                    put("system_available_mem_bytes", systemMemory.availMem); put("system_low_memory", systemMemory.lowMemory)
                    put("surface_state", surfaceState); put("session_generation", observedSessionGeneration)
                    toolName?.let { put("model_tool_name", it) }
                    redactedPayload?.let {
                        put("redacted_tool_result_payload", it)
                        put("tool_result_payload_length", promptOrPayload.length)
                    }
                    put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                }
                    synchronized(observationLock) { observationSink?.invoke(record) }
                },
                surfaceState = { currentSurfaceState },
                protocol = { record -> synchronized(observationLock) { protocolSink?.invoke(record) } },
                conversationConfig = { record -> synchronized(observationLock) { configSink?.invoke(record) } },
                captureConversationConfig = captureConversationConfig,
            )
            val manager = AgentSessionManager(store, gateway, AppContainer.SYSTEM_INSTRUCTION, "ko-KR")
            val kernel = AgentKernel(
                registry = registry, toolExecutor = surfaceExecutor, policyEngine = DefaultToolPolicyEngine(),
                sessionManager = manager, observationMapper = DefaultToolObservationMapper(), environment = AutoConfirmAndroidEnvironment(),
                turnPolicy = AgentTurnPolicy(maxToolCalls = 6, maxProtocolCorrections = 1, historyStrategy = ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP),
                contextSelector = ModelContextSelector(deployment.budget),
                contextPreflight = { snapshot -> ContextPreflight.check(deployment, AppContainer.SYSTEM_INSTRUCTION, ContextPreflight.toolCatalogText(snapshot.contractsByModelName.values)) },
                contactDirectory = contactDirectory, runtimeCounters = counters,
            )
            return ScenarioRuntime(store, gateway, manager, kernel)
        }
        surfaceExecutor = SurfaceTracingExecutor(
            delegate = DefaultToolExecutor(registry),
            instrumentation = instrumentation,
            dismissExternalSurfaces = dismissExternalSurfaces,
            onSurfaceState = { currentSurfaceState = it },
        )
        val runId = args.getString("a8RunId")?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }
            ?: "${mode}-${System.currentTimeMillis()}"
        val runDir = File(requireNotNull(context.getExternalFilesDir("a8")), runId)
        check(!runDir.exists()) { "refusing to overwrite A-8 evidence: ${runDir.absolutePath}" }
        check(runDir.mkdirs()) { "cannot create ${runDir.absolutePath}" }
        val rawPartial = File(runDir, "raw_turns.jsonl.partial")
        val tracesPartial = File(runDir, "scenario_turn_traces.jsonl.partial")
        val nativeObservationPartial = File(runDir, "native_invocations.jsonl.partial")
        // Raw protocol is intentionally a local diagnostic artifact only.  It is never promoted
        // into the normal evaluation trace or emitted through logcat.
        val nativeProtocolPartial = File(runDir, "native_protocol.local.jsonl.partial")
        val structuredTelemetryPartial = File(runDir, "structured_observability.jsonl.partial")
        val conversationConfigLocal = File(runDir, "conversation_config.local.json")
        observationSink = { record ->
            nativeObservationPartial.appendText(record.toString() + "\n", Charsets.UTF_8)
        }
        protocolSink = { record ->
            nativeProtocolPartial.appendText(record.toString() + "\n", Charsets.UTF_8)
        }
        configSink = if (captureConversationConfig) {
            { record ->
                check(!conversationConfigLocal.exists()) { "conversation config must be captured once" }
                conversationConfigLocal.writeText(record.toString(), Charsets.UTF_8)
            }
        } else null
        val started = SystemClock.elapsedRealtime()
        var turnCount = 0
        var scenarioCount = 0
        var confirmationEvents = 0
        var hybridTurns = 0
        val isolationEvidence = mutableListOf<JsonObject>()

        fun manifest(completed: Boolean) = buildJsonObject {
            put("schema", "hjp_multiturn_toolcall_device_run/v1")
            put("completed", completed)
            put("mode", mode); put("run_id", runId)
            put("gold_source", "tools/agent_eval_multiturn_v1/data/eval_set_v1_e32.json")
            put("gold_sha256", EVAL_SHA256); put("gold_contract", "E-3.2")
            put("raw_schema", "hjp_multiturn_toolcall_eval_raw/v1")
            put("device_model", Build.MODEL); put("device_abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            put("generative_artifact", deployment.artifactId); put("generative_sha256", deployment.verifiedSha256.orEmpty())
            put("embedding_engine", embedding.name()); put("embedding_model_backed", embedding.isModelBacked())
            put("dataset_kind", "evaluation-injected frozen 1,000-card dataset; not shipping default asset")
            put("card_dataset_sha256", FROZEN_CARDS_SHA256); put("context_budget", deployment.budget.maxPromptTokens)
            put("history_strategy", ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP.name)
            put("actual_android_intent_plugins", true); put("recording_or_fake_backend", false)
            put("auto_confirmation_for_local_mutation", true)
            put("external_surface_policy", "actual Intent opened; runner closes only its Resolver and never selects Send/Save")
            put("runner_dismisses_external_surfaces", dismissExternalSurfaces)
            put("backend_preference", backendPreference.name)
            put("lifecycle_repeats", lifecycleRepeats)
            put("reset_between_scenarios", resetBetweenScenarios)
            putJsonArray("scenario_isolation") { isolationEvidence.forEach { add(it) } }
            put("scenarios_completed", scenarioCount); put("turns_completed", turnCount)
            put("hybrid_turns", hybridTurns); put("confirmation_events", confirmationEvents)
            put("embedding_document_calls", embedding.documentCalls); put("embedding_query_calls", embedding.queryCalls)
            put("warmup_document_calls", fullIndexDocumentCalls)
            put("model_runtime_counters", buildJsonObject {
                counters.snapshot().asLongMap().forEach { (key, value) -> put(key, value) }
            })
            val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            put("pid", Process.myPid()); put("total_pss_kb", memory.totalPss); put("native_pss_kb", memory.nativePss)
            put("elapsed_ms", SystemClock.elapsedRealtime() - started)
        }

        try {
            rawPartial.bufferedWriter(Charsets.UTF_8).use { rawSink ->
                tracesPartial.bufferedWriter(Charsets.UTF_8).use { traceSink ->
                    lifecycleSelected.forEach { (scenarioElement, lifecycleRun) ->
                        val scenario = scenarioElement.jsonObject
                        val sid = scenario.getValue("scenario_id").jsonPrimitive.content
                        val runtime = createScenarioRuntime()
                        activeSessionStore = runtime.sessionStore
                        activeObservingGateway = runtime.gateway
                        isolationEvidence += buildJsonObject {
                            put("scenario_id", sid); put("lifecycle_run", lifecycleRun); put("event", "engine_create")
                            put("pid", Process.myPid()); put("engine_identity", System.identityHashCode(runtime.gateway))
                            put("timestamp_ms", SystemClock.elapsedRealtime())
                        }
                        Log.i(TAG, "A11_SCENARIO_ENGINE_CREATE scenario=$sid lifecycle=$lifecycleRun pid=${Process.myPid()}")
                        observedSessionGeneration = runtime.sessionStore.getOrCreate().generation
                        runtime.gateway.sessionGeneration = observedSessionGeneration
                        runtime.gateway.currentScenario = sid
                        runtime.gateway.lifecycleRun = lifecycleRun
                        val beforeCards = frozenCards.associateBy { it.id }
                        try {
                        val turns = buildJsonArray {
                            scenario.getValue("turns").jsonArray.forEach { turnElement ->
                                val spec = turnElement.jsonObject
                                val index = spec.getValue("index").jsonPrimitive.content.toInt()
                                val user = spec.getValue("user").jsonPrimitive.content
                                runtime.gateway.currentTurn = "$sid/$index"
                                surfaceExecutor.currentTurn = "$sid/$index"
                                surfaceExecutor.clearTurn()
                                val modelFrom = runtime.gateway.traces.size
                                val toolFrom = surfaceExecutor.traces.size
                                val retrievalFrom = retrieval.size
                                val startedTurn = SystemClock.elapsedRealtime()
                                Log.i(TAG, "A10_RUNNER_TURN_BEGIN scenario=$sid turn=$index pid=${Process.myPid()}")
                                var turnException: Throwable? = null
                                val events = try {
                                    runtime.kernel.runTurn(user).toList()
                                } catch (error: Throwable) {
                                    turnException = error
                                    Log.e(TAG, "A11_TURN_EXCEPTION scenario=$sid turn=$index", error)
                                    emptyList()
                                }
                                Log.i(TAG, "A10_RUNNER_TURN_RETURN scenario=$sid turn=$index pid=${Process.myPid()}")
                                val toolTrace = surfaceExecutor.traces.subList(toolFrom, surfaceExecutor.traces.size).toList()
                                val models = runtime.gateway.traces.subList(modelFrom, runtime.gateway.traces.size).toList()
                                val final = events.filterIsInstance<AgentEvent.FinalMessage>().lastOrNull()?.text
                                    ?: events.filterIsInstance<AgentEvent.UserError>().lastOrNull()?.messageKo.orEmpty()
                                val actualTurnId = events.filterIsInstance<AgentEvent.TurnStarted>().lastOrNull()?.turnId.orEmpty()
                                val outcome = runtime.sessionStore.getOrCreate().conversationMemory.actions.lastOrNull {
                                    it.turnId == actualTurnId
                                }
                                val selectedCard = runtime.sessionStore.getOrCreate().conversationMemory.selectedContact?.cardId.orEmpty()
                                val candidates = runtime.sessionStore.getOrCreate().conversationMemory.candidateContacts.map { it.cardId }
                                val turnRetrieval = retrieval.subList(retrievalFrom, retrieval.size).toList()
                                if (turnRetrieval.any { it.mode == "HYBRID" && !it.fallbackUsed }) hybridTurns++
                                confirmationEvents += events.count { it is AgentEvent.ConfirmationRequested }
                                val turnJson = buildJsonObject {
                                    put("index", index); put("user", user)
                                    putJsonArray("executed_tools") { toolTrace.forEach { add(JsonPrimitive(it.tool)) } }
                                    putJsonArray("tool_arguments") { toolTrace.forEach { trace ->
                                        add(buildJsonObject { put("tool", trace.tool); put("arguments", trace.arguments) })
                                    } }
                                    put("answer", final); put("is_error", events.any { it is AgentEvent.UserError })
                                    turnException?.let { put("runner_exception", it.toString()) }
                                    put("act", runtime.kernel.diagnostics.last?.routePlan.orEmpty())
                                    put("outcome_type", outcome?.status?.name.orEmpty())
                                    put("selected_card_id", selectedCard)
                                    putJsonArray("candidate_card_ids") { candidates.forEach { add(JsonPrimitive(it)) } }
                                    putJsonArray("search_rankings") { turnRetrieval.forEach { diagnostic ->
                                        add(buildJsonArray { diagnostic.rankedCardIds.forEach { add(JsonPrimitive(it)) } })
                                    } }
                                    putJsonArray("retrieval_modes") { turnRetrieval.forEach { add(JsonPrimitive(it.mode)) } }
                                    putJsonArray("new_compose_drafts") { toolTrace.filter { it.tool == "open_compose" && it.success }.forEach { trace ->
                                        add(buildJsonObject {
                                            put("channel", trace.arguments.string("channel").orEmpty().uppercase())
                                            put("to", trace.arguments.string("to").orEmpty())
                                            put("subject", trace.arguments.string("subject").orEmpty())
                                            put("body", trace.arguments.string("body").orEmpty())
                                        })
                                    } }
                                    putJsonArray("new_calendar_drafts") { toolTrace.filter { it.tool == "create_calendar_event" && it.success }.forEach { trace ->
                                        add(buildJsonObject {
                                            put("title", trace.arguments.string("title").orEmpty())
                                            put("start_millis", trace.arguments.string("start_time").orEmpty())
                                            put("end_millis", trace.arguments.string("end_time").orEmpty())
                                            put("location", trace.arguments.string("location").orEmpty())
                                            put("description", trace.arguments.string("description").orEmpty())
                                        })
                                    } }
                                    // Additional evidence; the JVM scorer reads only the fields above.
                                    put("a8_elapsed_ms", SystemClock.elapsedRealtime() - startedTurn)
                                    put("a8_context_tokens", runtime.kernel.diagnostics.last?.promptTokensEstimated ?: 0)
                                    put("a8_prompt_sections", buildJsonArray { runtime.kernel.diagnostics.last?.promptSections.orEmpty().forEach { add(it) } })
                                    put("a8_confirmation_requested", events.any { it is AgentEvent.ConfirmationRequested })
                                    put("a8_surface_dismissals", surfaceExecutor.dismissalsThisTurn)
                                }
                                add(turnJson)
                                traceSink.write(buildJsonObject {
                                    put("scenario_id", sid); put("lifecycle_run", lifecycleRun); put("turn", index); put("raw_turn", turnJson)
                                    put("session", runtime.sessionStore.getOrCreate().toJson())
                                    putJsonArray("model_invocations") { models.forEach { add(it.toJson()) } }
                                    putJsonArray("tool_invocations") { toolTrace.forEach { add(it.toJson()) } }
                                }.toString())
                                traceSink.write("\n"); traceSink.flush()
                                if (structuredTelemetryEnabled) {
                                    val diag = runtime.kernel.diagnostics.last
                                    val lastDecision = models.lastOrNull()?.decision
                                    val telemetry = buildJsonObject {
                                        put("schema", "hjp_structured_observability/v1")
                                        put("scenario_id", sid); put("turn_id", "$sid/$index")
                                        put("lifecycle_run", lifecycleRun); put("pid", Process.myPid())
                                        put("route_plan", diag?.routePlan.orEmpty()); put("dialogue_act", diag?.dialogueAct.orEmpty())
                                        put("evidence", diag?.evidence.orEmpty()); put("outcome", diag?.outcome.orEmpty())
                                        put("current_target_card_id", selectedCard)
                                        put("selected_contact_card_id", runtime.sessionStore.getOrCreate().conversationMemory.selectedContact?.cardId.orEmpty())
                                        putJsonArray("candidate_card_ids") { candidates.forEach { add(JsonPrimitive(it)) } }
                                        put("candidate_count", candidates.size)
                                        put("correction_count", runtime.sessionStore.getOrCreate().conversationMemory.corrections.size)
                                        put("pending_workflow", runtime.kernel.diagnostics.last?.routePlan.orEmpty())
                                        put("search_required", diag?.dialogueAct == "SEARCH")
                                        put("fresh_read_required", diag?.nativeContext?.totalTokens?.let { false } ?: false)
                                        put("clarification_required", diag?.dialogueAct == "CLARIFICATION")
                                        putJsonArray("projected_sections") { diag?.promptSections.orEmpty().forEach { add(it) } }
                                        put("projected_target_present", selectedCard.isNotEmpty())
                                        put("projected_candidate_count", candidates.size)
                                        putJsonArray("model_tools") {
                                            when (lastDecision) {
                                                is ModelDecision.ToolCalls -> lastDecision.calls.forEach { call ->
                                                    add(buildJsonObject { put("name", call.modelToolName); put("card_id", call.arguments.string("card_id").orEmpty()) })
                                                }
                                                else -> {}
                                            }
                                        }
                                        val modelCalls = (lastDecision as? ModelDecision.ToolCalls)?.calls.orEmpty()
                                        val turnExecutions = toolTrace.filter { it.turn == "$sid/$index" }
                                        put("raw_model_toolcall_present", modelCalls.isNotEmpty())
                                        put("parsed_toolcall", if (modelCalls.isNotEmpty()) "SUCCESS" else if (lastDecision is ModelDecision.Invalid) "FAILURE" else "NONE")
                                        put("parser_status", if (lastDecision is ModelDecision.Invalid) "FAILURE" else "SUCCESS_OR_NONE")
                                        put("parser_reason", (lastDecision as? ModelDecision.Invalid)?.safeReason.orEmpty())
                                        put("schema_validation", "NOT_EXPOSED_BY_PRODUCTION_GATEWAY")
                                        put("schema_validation_reason", "NOT_EXPOSED_BY_PRODUCTION_GATEWAY")
                                        put("argument_normalization", "NOT_OBSERVED")
                                        put("policy_decision", "NOT_EXPOSED")
                                        put("guard_decision", "NOT_EXPOSED")
                                        put("deterministic_obligation", if (diag?.dialogueAct == "ACTION_CALENDAR") "CALENDAR_ACTION" else "NONE_OR_UNAVAILABLE")
                                        put("executor_dispatch", turnExecutions.isNotEmpty())
                                        put("executor_result", turnExecutions.lastOrNull()?.let { if (it.success) "SUCCESS" else "REJECTED" } ?: "NOT_DISPATCHED")
                                        put("executor_rejection_reason", turnExecutions.lastOrNull()?.takeUnless { it.success }?.result.orEmpty())
                                        putJsonArray("final_executed_tools") { turnExecutions.forEach { add(it.tool) } }
                                        putJsonArray("executed_tools") { turnExecutions.forEach { add(it.tool) } }
                                        put("deterministic_override", false)
                                    }
                                    structuredTelemetryPartial.appendText(telemetry.toString() + "\n", Charsets.UTF_8)
                                }
                                turnCount++
                            }
                        }
                        val currentRows = dao.loadAll().associateBy { it.id }
                        val mutated = frozenCards.filter { base -> currentRows[base.id] != base }.map { it.id }
                        rawSink.write(buildJsonObject {
                            put("scenario_id", sid)
                            put("lifecycle_run", lifecycleRun)
                            put("split", scenario.getValue("split").jsonPrimitive.content)
                            put("category", scenario.getValue("category").jsonPrimitive.content)
                            put("workflow", scenario.getValue("workflow").jsonPrimitive.content)
                            put("turn_count", scenario.getValue("turn_count").jsonPrimitive.content.toInt())
                            put("final_selected_card_id", runtime.sessionStore.getOrCreate().conversationMemory.selectedContact?.cardId.orEmpty())
                            put("compose_draft_count", turns.jsonArray.sumOf { it.jsonObject.getValue("new_compose_drafts").jsonArray.size })
                            put("calendar_draft_count", turns.jsonArray.sumOf { it.jsonObject.getValue("new_calendar_drafts").jsonArray.size })
                            putJsonArray("mutated_cards") { mutated.forEach { add(JsonPrimitive(it)) } }
                            putJsonArray("mutated_card_titles") { mutated.forEach { id ->
                                currentRows[id]?.let { row -> add(buildJsonObject {
                                    put("card_id", id); put("title", row.title); put("company", row.company); put("memo", row.memo)
                                }) }
                            } }
                            put("turns", turns)
                            put("a8_model_calls", runtime.gateway.traces.count { it.turn.startsWith("$sid/") })
                        }.toString())
                        rawSink.write("\n"); rawSink.flush()
                        // Only rows changed by this scenario are restored. This preserves the shared
                        // 1,000-vector index; a changed row may need one normal stale-vector repair later.
                        if (mutated.isNotEmpty()) {
                            mutated.forEach { id -> dao.update(beforeCards.getValue(id)) }
                            dao.rebuildFts()
                            backend.invalidate(); contactDirectory.invalidate()
                        }
                        scenarioCount++
                        } finally {
                            runCatching { runtime.kernel.close() }
                            isolationEvidence += buildJsonObject {
                                put("scenario_id", sid); put("lifecycle_run", lifecycleRun); put("event", "engine_close")
                                put("pid", Process.myPid()); put("engine_identity", System.identityHashCode(runtime.gateway))
                                put("timestamp_ms", SystemClock.elapsedRealtime())
                            }
                            Log.i(TAG, "A11_SCENARIO_ENGINE_CLOSE scenario=$sid lifecycle=$lifecycleRun pid=${Process.myPid()}")
                            activeSessionStore = null; activeObservingGateway = null
                        }
                        if (scenarioCount % 5 == 0 || scenarioCount == lifecycleSelected.size) {
                            File(runDir, "run_manifest.json").writeText(PRETTY.encodeToString(JsonObject.serializer(), manifest(false)))
                            Log.i(TAG, "progress mode=$mode scenarios=$scenarioCount/${selected.size} turns=$turnCount model=${counters.snapshot().modelInvocationSuccesses}")
                        }
                    }
                }
            }
            assertEquals(lifecycleSelected.size, scenarioCount)
            assertTrue("no actual native model invocation", counters.snapshot().modelInvocationSuccesses > 0)
            assertTrue("no model-backed hybrid retrieval", hybridRetrievalVerified)
            assertTrue("raw output missing", rawPartial.length() > 0L)
            assertTrue("trace output missing", tracesPartial.length() > 0L)
            check(rawPartial.renameTo(File(runDir, "raw_turns.jsonl"))) { "cannot promote raw output" }
            check(tracesPartial.renameTo(File(runDir, "scenario_turn_traces.jsonl"))) { "cannot promote trace output" }
            check(nativeObservationPartial.renameTo(File(runDir, "native_invocations.jsonl"))) { "cannot promote native observation output" }
            check(nativeProtocolPartial.renameTo(File(runDir, "native_protocol.local.jsonl"))) { "cannot promote native protocol output" }
            if (structuredTelemetryEnabled) check(structuredTelemetryPartial.renameTo(File(runDir, "structured_observability.jsonl"))) { "cannot promote structured telemetry output" }
            File(runDir, "run_manifest.json").writeText(PRETTY.encodeToString(JsonObject.serializer(), manifest(true)))
            Log.i(TAG, "A8_COMPLETE mode=$mode run_dir=${runDir.absolutePath} scenarios=$scenarioCount turns=$turnCount")
        } finally {
            // Leave the app's evaluation fixture exactly frozen; the host still restores the original
            // DB/WAL/SHM backup after the instrumentation process returns.
            runCatching { restoreFrozenRows(dao, frozenCards) }
            runCatching { nativeEmbedding.close() }
            runCatching { database.close() }
        }
    }

    private suspend fun restoreFrozenRows(dao: com.example.hjp.data.BusinessCardDao, frozen: List<BusinessCardEntity>) {
        val existing = dao.loadAll().associateBy { it.id }
        val frozenIds = frozen.mapTo(linkedSetOf()) { it.id }
        // The evaluation fixture is exactly 1,000 rows.  Remove only non-fixture rows; this leaves
        // embeddings for the frozen IDs intact and prevents an earlier diagnostic test card from
        // silently changing search ranking or scorer semantics.
        (existing.keys - frozenIds).forEach { dao.deleteCard(it) }
        frozen.forEach { card ->
            if (existing.containsKey(card.id)) dao.update(card) else dao.insertAll(listOf(card))
        }
        dao.rebuildFts()
    }

    private data class ModelTrace(val turn: String, val kind: String, val prompt: String = "", val payload: String = "", val decision: ModelDecision) {
        fun toJson() = buildJsonObject {
            put("turn", turn); put("kind", kind); put("rendered_prompt", prompt)
            put("rendered_prompt_sha256", sha256(prompt.toByteArray()))
            put("payload", payload); put("decision", decisionJson(decision))
        }
    }

    private class ObservingGateway(
        private val delegate: AgentModelGateway,
        private val observation: (String, Int, String, String, String, Int, String, String, String?, JsonObject?) -> Unit,
        private val surfaceState: () -> String,
        private val protocol: (JsonObject) -> Unit,
        private val conversationConfig: (JsonObject) -> Unit,
        private val captureConversationConfig: Boolean,
    ) : AgentModelGateway {
        var currentScenario = "setup"
        var currentTurn = "setup"
        var lifecycleRun = 1
        var sessionGeneration = -1L
        private var invocationOrdinal = 0
        private var configuredContextLimit = 3_072
        private var configuredMaxOutput = 1_024
        private var configuredSystemText = ""
        private var configuredToolCatalogText = ""
        val traces = mutableListOf<ModelTrace>()
        /** Test evidence preserves protocol shape without copying contact PII to shared storage. */
        fun redactToolPayload(payload: JsonObject): JsonObject =
            JsonObject(payload.mapValues { (key, value) -> redactJson(value, key) })

        private fun redactJson(value: JsonElement, key: String? = null): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (childKey, childValue) -> redactJson(childValue, childKey) })
            is JsonArray -> JsonArray(value.map { redactJson(it, key) })
            JsonNull -> JsonNull
            else -> if (key?.lowercase() in REDACTED_PAYLOAD_KEYS) JsonPrimitive("<redacted>") else value
        }

        override val boundaryKind get() = delegate.boundaryKind
        fun protocolRecord(
            phase: String,
            ordinal: Int,
            kind: String,
            payload: String,
            toolName: String? = null,
            decision: ModelDecision? = null,
            sectionTokens: Map<String, Int> = emptyMap(),
        ) = protocol(buildJsonObject {
            put("phase", phase); put("invocation_ordinal", ordinal); put("kind", kind)
            put("scenario_id", currentScenario); put("turn", currentTurn); put("lifecycle_run", lifecycleRun)
            put("session_generation", sessionGeneration)
            put("raw_input", payload); put("input_sha256", sha256(payload.toByteArray())); put("input_length", payload.length)
            val inputTokens = calibratedTokenEstimate(payload)
            put("token_telemetry", buildJsonObject {
                put("total_input_tokens", inputTokens)
                put("total_input_tokens_quality", "ESTIMATED")
                put("system_tokens", calibratedTokenEstimate(configuredSystemText))
                put("system_tokens_quality", "ESTIMATED")
                put("tool_catalog_tokens", calibratedTokenEstimate(configuredToolCatalogText))
                put("tool_catalog_tokens_quality", "ESTIMATED")
                put("history_conversation_tokens", 0)
                put("history_conversation_tokens_quality", "UNAVAILABLE_FOR_NATIVE_CONTINUATION")
                put("workflow_context_tokens", 0)
                put("workflow_context_tokens_quality", "UNAVAILABLE_FOR_NATIVE_CONTINUATION")
                put("current_request_tokens", if (kind == "user") inputTokens else 0)
                put("current_request_tokens_quality", if (kind == "user") "ESTIMATED" else "NOT_APPLICABLE")
                put("configured_context_limit", configuredContextLimit)
                put("configured_max_output_tokens", configuredMaxOutput)
                put("remaining_headroom_tokens", (configuredContextLimit - inputTokens).coerceAtLeast(0))
                put("truncation", false)
                put("truncation_status", "NOT_OBSERVED")
                put("section_tokens", buildJsonObject { sectionTokens.forEach { (name, tokens) -> put(name, tokens) } })
                put("output_tokens", decision?.let { calibratedTokenEstimate(decisionJson(it).toString()) } ?: 0)
                put("output_tokens_quality", if (decision != null) "ESTIMATED" else "NOT_AVAILABLE")
                put("output_limit_reached", false)
                put("termination_reason", if (decision != null) "MODEL_DECISION_RETURNED" else "NOT_RETURNED")
            })
            toolName?.let { put("model_tool_name", it) }
            decision?.let { put("model_decision", decisionJson(it)) }
        })

        override suspend fun openSession(config: ModelSessionConfig): AgentModelSession {
            if (!captureConversationConfig) return Session(delegate.openSession(config), this)
            val tools = config.toolCatalog.contractsByModelName.values.sortedBy { it.modelName }.map { contract ->
                buildJsonObject {
                    put("model_name", contract.modelName); put("capability_id", contract.capabilityId.value)
                    put("version", contract.version.toString()); put("description", contract.description)
                    put("input_schema", contract.inputSchema); put("output_schema", contract.outputSchema)
                    put("effect", contract.effect.name); put("confirmation_policy", contract.confirmationPolicy.name)
                }
            }
            val canonical = buildJsonObject {
                put("system_instruction", config.systemInstruction); put("locale_tag", config.localeTag)
                put("sampler", buildJsonObject {
                    put("temperature", config.samplingProfile.temperature); put("top_k", config.samplingProfile.topK)
                    put("top_p", config.samplingProfile.topP); put("max_output_tokens", config.samplingProfile.maxOutputTokens)
                })
                put("automatic_tool_calling", false); put("backend_preference", "runner-selected")
                put("tool_declarations", JsonArray(tools))
                put("native_conversation_snapshot_api", "not_exposed_by_litertlm-android API used by production")
            }.toString()
            configuredMaxOutput = config.samplingProfile.maxOutputTokens
            configuredSystemText = config.systemInstruction
            configuredToolCatalogText = tools.toString()
            conversationConfig(buildJsonObject {
                put("canonical_config", canonical); put("sha256", sha256(canonical.toByteArray()))
                put("length", canonical.length)
            })
            return Session(delegate.openSession(config), this)
        }
        override fun close() = delegate.close()
        private class Session(private val delegate: AgentModelSession, private val owner: ObservingGateway) : AgentModelSession {
            override val catalogRevision get() = delegate.catalogRevision
        override suspend fun decide(input: ModelInput): ModelDecision {
                val user = input as ModelInput.User
                val rendered = user.promptContext.render(user.text)
                val ordinal = ++owner.invocationOrdinal
                val sections = user.promptContext.sections.associate { it.name to calibratedTokenEstimate(it.body) }
                owner.protocolRecord("send_begin", ordinal, "user", rendered, sectionTokens = sections)
                owner.observation("begin", ordinal, "user", owner.currentScenario, owner.currentTurn, owner.lifecycleRun, rendered, owner.surfaceState(), null, null)
                val decision = delegate.decide(input)
                owner.traces += ModelTrace(owner.currentTurn, "user", rendered, decision = decision)
                owner.protocolRecord("send_return", ordinal, "user", rendered, decision = decision, sectionTokens = sections)
                owner.observation("return", ordinal, "user", owner.currentScenario, owner.currentTurn, owner.lifecycleRun, rendered, owner.surfaceState(), null, null)
                return decision
            }
            override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision {
                val payload = result.payload.toString()
                val redacted = owner.redactToolPayload(result.payload)
                val ordinal = ++owner.invocationOrdinal
                owner.protocolRecord("send_begin", ordinal, "tool_result", payload, result.modelToolName)
                owner.observation("begin", ordinal, "tool_result", owner.currentScenario, owner.currentTurn, owner.lifecycleRun, payload, owner.surfaceState(), result.modelToolName, redacted)
                val decision = delegate.continueWithToolResult(result)
                owner.traces += ModelTrace(owner.currentTurn, "tool_result", payload = payload, decision = decision)
                owner.protocolRecord("send_return", ordinal, "tool_result", payload, result.modelToolName, decision)
                owner.observation("return", ordinal, "tool_result", owner.currentScenario, owner.currentTurn, owner.lifecycleRun, payload, owner.surfaceState(), result.modelToolName, redacted)
                return decision
            }
            override suspend fun continueWithWorkflowNote(note: com.hjp.agent.contract.ModelWorkflowNote): ModelDecision {
                val ordinal = ++owner.invocationOrdinal
                owner.protocolRecord("send_begin", ordinal, "workflow_note", note.text)
                owner.observation("begin", ordinal, "workflow_note", owner.currentScenario, owner.currentTurn, owner.lifecycleRun, note.text, owner.surfaceState(), null, null)
                val decision = delegate.continueWithWorkflowNote(note)
                owner.traces += ModelTrace(owner.currentTurn, "workflow_note", payload = note.text, decision = decision)
                owner.protocolRecord("send_return", ordinal, "workflow_note", note.text, decision = decision)
                owner.observation("return", ordinal, "workflow_note", owner.currentScenario, owner.currentTurn, owner.lifecycleRun, note.text, owner.surfaceState(), null, null)
                return decision
            }
            override fun streamFinal(input: FinalAnswerInput): Flow<String> = delegate.streamFinal(input)
            override suspend fun resetConversation() = delegate.resetConversation()
            override fun close() = delegate.close()
        }
    }

    private data class ToolTrace(val turn: String, val tool: String, val arguments: JsonObject, val success: Boolean, val result: String, val resultData: JsonObject?, val resolverDismissed: Boolean) {
        fun toJson() = buildJsonObject {
            put("turn", turn); put("tool", tool); put("arguments", arguments); put("success", success)
            put("result", result); resultData?.let { put("result_data", it) }; put("resolver_dismissed", resolverDismissed)
        }
    }

    private class SurfaceTracingExecutor(
        private val delegate: ToolExecutor,
        private val instrumentation: android.app.Instrumentation,
        private val dismissExternalSurfaces: Boolean,
        private val onSurfaceState: (String) -> Unit,
    ) : ToolExecutor {
        var currentTurn = "setup"
        val traces = mutableListOf<ToolTrace>()
        var dismissalsThisTurn = 0
            private set
        fun clearTurn() { dismissalsThisTurn = 0 }
        override suspend fun execute(call: ModelToolCall, snapshot: ToolCatalogSnapshot, context: ToolExecutionContext): ToolExecutionResult {
            val result = delegate.execute(call, snapshot, context)
            val dismissed = if (dismissExternalSurfaces && result is ToolExecutionResult.Success && call.modelToolName in EXTERNAL_TOOLS) dismissOwnedResolver(call.modelToolName) else false
            if (call.modelToolName in EXTERNAL_TOOLS) onSurfaceState("${call.modelToolName}:dismissed=$dismissed")
            if (dismissed) dismissalsThisTurn++
            traces += ToolTrace(currentTurn, call.modelToolName, call.arguments, result is ToolExecutionResult.Success, result.toString(), (result as? ToolExecutionResult.Success)?.data, dismissed)
            return result
        }
        private fun dismissOwnedResolver(tool: String): Boolean {
            val action = if (tool == "open_compose") "android.intent.action.SENDTO" else "android.intent.action.INSERT"
            val deadline = SystemClock.elapsedRealtime() + 2_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities"),
                ).bufferedReader().use { it.readText() }
                if (text.contains("launchedFromPackage=com.example.hjp") && text.contains("act=$action") && text.contains("ResolverActivity")) {
                    return instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
                SystemClock.sleep(25L)
            }
            return false
        }
    }

    private class CountingEmbeddingEngine(private val delegate: OnDeviceEmbeddingEngine) : EmbeddingEngine {
        var documentCalls = 0; var queryCalls = 0
        override fun embed(input: String): FloatArray = embedQuery(input)
        override fun embedQuery(input: String): FloatArray { queryCalls++; return delegate.embedQuery(input) }
        override fun embedDocument(input: String): FloatArray { documentCalls++; return delegate.embedDocument(input) }
        override fun name() = delegate.name()
        override fun isModelBacked() = delegate.isModelBacked()
        override fun diagnosticStatus() = delegate.diagnosticStatus()
    }

    private class AutoConfirmAndroidEnvironment : AgentRuntimeEnvironment {
        override val localeTag = "ko-KR"
        override val timeZoneId = "Asia/Seoul"
        override suspend fun grantedPermissions() = emptySet<String>()
        override suspend fun deviceCapabilities() = setOf("android.external_ui", "contact.local_search", "contact.local_update", "datetime.current")
        override suspend fun toolContext(sessionId: String, turnId: String) = ToolExecutionContext(
            sessionId, turnId, localeTag, timeZoneId, confirmationGateway = ConfirmationGateway { true },
        )
    }

    private fun AgentSession.toJson() = buildJsonObject {
        put("session_id", sessionId); put("generation", generation); put("selected_card_id", conversationMemory.selectedContact?.cardId.orEmpty())
        putJsonArray("candidate_card_ids") { conversationMemory.candidateContacts.forEach { add(JsonPrimitive(it.cardId)) } }
        putJsonArray("corrections") { conversationMemory.corrections.forEach { add(JsonPrimitive(it.content)) } }
    }

    private fun parseCards(raw: String): List<BusinessCardEntity> =
        json.parseToJsonElement(raw).jsonArray.map { element ->
            val card = element.jsonObject
            fun value(key: String) = (card[key] as? JsonPrimitive)?.content.orEmpty()
            val tags = value("tags").split(',').map(String::trim).filter(String::isNotEmpty)
            BusinessCardEntity(value("id"), value("name"), value("nameEn"), value("company"), value("title"), value("department"), value("industry"), value("location"), value("phone"), "", value("email"), value("address"), "", value("memo"), json.encodeToString(tags), "")
        }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.content?.trim()

    private companion object {
        private val REDACTED_PAYLOAD_KEYS = setOf(
            "name", "name_en", "email", "phone", "mobile", "address", "memo", "card_id",
            "company", "department", "location", "website", "tags", "message", "user_message_ko",
        )

        const val TAG = "HjpA8DeviceEval"
        const val EVAL_ASSET = "agent_eval/eval_set_v1_e32.json"
        const val CARD_ASSET = "ryeong/cards_eval1000.json"
        const val EVAL_SHA256 = "1eacb9f831be395fd70c1c520c4847cdadda02a4df4b142a9dce50bc1a0a92da"
        const val FROZEN_CARDS_SHA256 = "f0feaebfdf5eb26c2a161a4b8c40d1307a6f5fa9c68f00309f05b69d03e7cd24"
        const val EXPECTED_SCENARIOS = 400
        const val EXPECTED_TURNS = 1_918
        const val EXPECTED_CARDS = 1_000
        const val WARMUP_QUERY = "블루오션컨설팅 AI 개발자"
        val REPRESENTATIVE_IDS = listOf("DEV-0001", "DEV-0004", "DEV-0005", "DEV-0008", "DEV-0012")
        val EXTERNAL_TOOLS = setOf("open_compose", "create_calendar_event")
        val PRETTY = Json { prettyPrint = true }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        /** Conservative diagnostic estimate; this is not the LiteRT SentencePiece tokenizer. */
        fun calibratedTokenEstimate(text: String): Int {
            if (text.isEmpty()) return 0
            var cjk = 0
            var other = 0
            text.forEach { if (it.code in 0xAC00..0xD7A3 || it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF) cjk++ else other++ }
            return maxOf(1, kotlin.math.ceil(cjk * 0.86 + other * 0.29).toInt())
        }
        fun decisionJson(decision: ModelDecision): JsonObject = when (decision) {
            is ModelDecision.ToolCalls -> buildJsonObject { put("type", "tool_calls"); putJsonArray("calls") { decision.calls.forEach { call -> add(buildJsonObject { put("name", call.modelToolName); put("arguments", call.arguments) }) } } }
            is ModelDecision.FinalCandidate -> buildJsonObject { put("type", "final"); put("text", decision.draftText); put("clarification", decision.clarification?.name.orEmpty()) }
            is ModelDecision.Invalid -> buildJsonObject { put("type", "invalid"); put("reason", decision.safeReason); put("retryable", decision.retryable) }
        }
    }
}
