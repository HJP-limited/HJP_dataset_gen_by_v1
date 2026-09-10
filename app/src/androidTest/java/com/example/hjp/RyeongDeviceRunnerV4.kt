package com.example.hjp

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.example.hjp.deviceeval.DeviceRunEvidence
import com.example.hjp.deviceeval.DeviceRunEvidence.RunMode
import com.example.hjp.search.AndroidEmbeddingGemmaEngine
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.AgentRuntimeCounters
import com.hjp.agent.contract.CountingAgentModelGateway
import com.hjp.agent.core.AgentKernel
import com.hjp.agent.core.AgentRuntimeEnvironment
import com.hjp.agent.core.AgentSessionManager
import com.hjp.agent.core.AgentTurnPolicy
import com.hjp.agent.core.CachingArtifactDigestProvider
import com.hjp.agent.core.ContactNameCandidates
import com.hjp.agent.core.ContextPreflight
import com.hjp.agent.core.ContextPreflightResult
import com.hjp.agent.core.ConversationHistoryStrategy
import com.hjp.agent.core.DefaultToolExecutor
import com.hjp.agent.core.DefaultToolObservationMapper
import com.hjp.agent.core.DefaultToolPolicyEngine
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.DeterministicTurnRouter
import com.hjp.agent.core.InMemoryAgentSessionStore
import com.hjp.agent.core.ModelContextSelector
import com.hjp.agent.core.ModelDeploymentResolver
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.agent.litert.LiteRtAgentModelGateway
import com.hjp.agent.litert.LiteRtBackendPreference
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.android.CalendarComposerBackend
import com.hjp.tool.android.CalendarDraft
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.MessageChannel
import com.hjp.tool.android.MessageComposerBackend
import com.hjp.tool.android.MessageDraft
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.ContactSearchBackend
import com.hjp.tool.contact.ContactSearchResponse
import com.hjp.tool.contact.GetContactPlugin
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.datetime.GetCurrentDateTimePlugin
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The device evaluation body, shared by the smoke class and the official class.
 *
 * One assembly, two modes. v3 had one class doing both jobs and the only thing separating a
 * five-scenario shakeout from the run of record was a number inside the result file; here the mode is
 * a parameter of the *evidence contract*, so a smoke run cannot learn the official directory, cannot
 * take the official invocation, and writes a run identifier that says what it is.
 *
 * Everything below the boundary is the production class: the real kernel, the real router, the real
 * session and memory, the real contact directory over a real Room database, the real Ryeong search
 * service, the real EmbeddingGemma engine and the real LiteRT gateway loading the real `.litertlm`.
 *
 * Two things are deliberately not real, and neither can change a routing or retrieval decision:
 * the calendar and message backends are recording fakes, so nothing is sent or saved on somebody's
 * device; and the card database is in-memory, so the evaluation fixture never lands in app storage.
 *
 * Nothing in this file runs unless an instrumentation argument asks for it.
 */
class RyeongDeviceRunnerV4(
    private val context: Context,
    private val mode: RunMode,
    private val scenarioLimit: Int,
) {

    /** What the run produced, for the caller to assert on after the evidence is on disk. */
    data class Outcome(
        val turnsRun: Int,
        val scenariosRun: Int,
        val promotionFailures: List<String>,
        val observationDisagreements: Int,
        val crossScenarioLeakage: Int,
        val counters: com.hjp.agent.contract.RuntimeCounterSnapshot,
        val validityFailures: List<String>,
    )

    fun run(): Outcome {
        // A capped official run would produce a short result carrying the official identifier, which
        // no later reader could tell from a complete one. Refused before anything else happens.
        DeviceRunEvidence.requireScenarioLimitAllowed(mode, scenarioLimit)

        val startedAt = System.currentTimeMillis()
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val paths = DeviceRunEvidence.Paths(root, mode).prepare()

        val isEmulator = Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true)
        val modelRoot = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val modelFile = File(modelRoot, "hjp-agent.litertlm")
        val deployment = ModelDeploymentResolver.resolve(
            modelFile, digestProvider = CachingArtifactDigestProvider(),
        )

        check(!isEmulator) { "this run requires a physical device" }
        check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "arm64-v8a required" }
        check(modelFile.exists()) { "model missing at ${modelFile.absolutePath}" }
        check(deployment.usable) { "model not usable: ${deployment.diagnosticSummary()}" }

        // ---- frozen inputs, verified by digest before anything reads them ------------------------
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val scenarioBytes = assets.open(SCENARIO_ASSET).use { it.readBytes() }
        val cardBytes = assets.open(CARD_ASSET).use { it.readBytes() }
        val scenarioSha = sha256(scenarioBytes)
        val cardSha = sha256(cardBytes)
        check(scenarioSha == FROZEN_SCENARIO_SHA256) { "frozen scenario digest: $scenarioSha" }
        check(cardSha == FROZEN_CARDS_SHA256) { "frozen card digest: $cardSha" }

        val scenarios = parseScenarios(String(scenarioBytes, Charsets.UTF_8))
        val cards = parseCards(String(cardBytes, Charsets.UTF_8))
        check(scenarios.size == EXPECTED_SCENARIOS) { "frozen scenario count ${scenarios.size}" }
        check(scenarios.sumOf { it.turns.size } == EXPECTED_TURNS) { "frozen turn count" }
        check(scenarios.map { it.kind }.toSet().size == EXPECTED_KINDS) { "frozen kind count" }
        check(cards.size == EXPECTED_CARDS) { "frozen card count ${cards.size}" }

        // ---- the official invocation, claimed atomically -----------------------------------------
        val markerText = invocationMarker(startedAt, scenarioSha, cardSha, modelFile, deployment)
        if (mode.countsAsOfficialInvocation) {
            when (val claim = DeviceRunEvidence.OfficialRunGuard.claim(paths, markerText, iso(startedAt))) {
                is DeviceRunEvidence.ClaimResult.Refused ->
                    error("RUN_D4 refused: ${claim.code} — ${claim.detail}")
                is DeviceRunEvidence.ClaimResult.Claimed -> Unit
            }
        } else {
            check(paths.existingFinalArtifacts().isEmpty()) {
                "smoke artefacts already present: ${paths.existingFinalArtifacts()}"
            }
            paths.stalePartials().forEach { File(paths.directory, it).delete() }
        }

        // ---- the production stack, assembled the way AppContainer assembles it ---------------------
        val counters = AgentRuntimeCounters()
        val database = Room.inMemoryDatabaseBuilder(context, HjpDatabase::class.java).build()
        val repository = RoomBusinessCardRepository(context, database.businessCardDao(), Json)
        runBlocking { database.businessCardDao().insertAllAndReindex(cards.map { it.toEntity() }) }

        val contactDirectory = RepositoryContactDirectory(repository)
        val embeddingEngine = AndroidEmbeddingGemmaEngine(context)
        val retrievalModes = linkedSetOf<String>()
        val realBackend = RyeongContactSearchBackend(
            repository = repository,
            embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(embeddingEngine) },
            diagnostics = { event ->
                // Counted from the backend's own declared mode, exactly as AppContainer does it.
                counters.recordRetrieval(
                    semanticUsed = event.mode != "KEYWORD_ONLY",
                    candidateCount = event.keywordResultCount + event.semanticResultCount,
                    semanticResultCount = event.semanticResultCount,
                    keywordFallback = event.fallbackUsed,
                    elapsedMillis = event.elapsedMillis,
                )
                if (event.initializationMillis > 0) {
                    counters.recordSemanticInitializationAttempt()
                    if (event.mode != "KEYWORD_ONLY") counters.recordSemanticInitializationSuccess()
                    else counters.recordSemanticInitializationFailure()
                }
                retrievalModes += event.mode
                android.util.Log.i(TAG, "search mode=${event.mode} fallback=${event.fallbackUsed} " +
                    "kw=${event.keywordResultCount} sem=${event.semanticResultCount} " +
                    "elapsed=${event.elapsedMillis}")
            },
        )
        val tracer = TracingBackend(realBackend)
        val calendar = RecordingCalendarBackend()
        val messages = RecordingMessageBackend()

        val plugins = listOf(
            SearchContactsPlugin(tracer),
            GetContactPlugin(tracer),
            UpdateBusinessCardPlugin(
                repository,
                onUpdated = { realBackend.invalidate(); contactDirectory.invalidate() },
            ),
            CreateCalendarEventPlugin(calendar),
            OpenComposePlugin(messages),
            GetCurrentDateTimePlugin(),
        )
        val registry = DefaultToolRegistry(plugins.map { ToolImplementationCandidate(it) })
        val store = InMemoryAgentSessionStore()
        // Counted, not replaced: the decorator forwards every call and returns the gateway's own
        // value, so "an actual model produced this" becomes a measurement rather than a claim.
        val gateway = CountingAgentModelGateway(
            LiteRtAgentModelGateway(
                modelFile,
                File(context.cacheDir, "litertlm-ryeong-eval-v4"),
                LiteRtBackendPreference.CPU_ONLY,
                counters = counters,
            ),
            counters,
        )
        val environment = EvaluationEnvironment()
        val sessionManager = AgentSessionManager(store, gateway, SYSTEM_INSTRUCTION, "ko-KR")
        val kernel = AgentKernel(
            registry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            policyEngine = DefaultToolPolicyEngine(),
            sessionManager = sessionManager,
            observationMapper = DefaultToolObservationMapper(),
            environment = environment,
            turnPolicy = AgentTurnPolicy(
                maxToolCalls = 6,
                maxProtocolCorrections = 1,
                historyStrategy = ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP,
            ),
            contextSelector = ModelContextSelector(deployment.budget),
            contextPreflight = { snapshot ->
                ContextPreflight.check(
                    deployment, SYSTEM_INSTRUCTION,
                    ContextPreflight.toolCatalogText(snapshot.contractsByModelName.values),
                )
            },
            contactDirectory = contactDirectory,
            runtimeCounters = counters,
        )

        val catalog = runBlocking {
            registry.snapshot(
                com.hjp.tool.contract.CatalogContext(
                    store.getOrCreate().sessionId, environment.localeTag,
                    environment.grantedPermissions(), environment.deviceCapabilities(),
                ),
            )
        }
        check(catalog.contractsByModelName.keys.toSortedSet() == PRODUCTION_TOOLS) {
            "tool catalog must be the production six: ${catalog.contractsByModelName.keys}"
        }
        when (
            ContextPreflight.check(
                deployment, SYSTEM_INSTRUCTION,
                ContextPreflight.toolCatalogText(catalog.contractsByModelName.values),
            )
        ) {
            is ContextPreflightResult.Ok -> Unit
            else -> error("context preflight failed")
        }

        // The directory has to resolve somebody out of *this* fixture. The contact is picked at
        // runtime, never written into this file, so the canary cannot pass by coincidence.
        val canaryCard = cards.firstOrNull { card ->
            card.name.length >= 2 && cards.count { it.name == card.name } == 1
        } ?: error("fixture has no uniquely-named contact to canary against")
        val canaryMatches = runBlocking {
            contactDirectory.resolve(ContactNameCandidates.candidates("${canaryCard.name}씨 회사가 어디야?"))
        }
        check(canaryMatches.any { it.name == canaryCard.name && it.identifiesAPerson }) {
            "contact directory resolved nothing for a real fixture contact — the store-backed " +
                "routing path is not wired"
        }

        // ---- the run ------------------------------------------------------------------------------
        val selected = if (scenarioLimit > 0) scenarios.take(scenarioLimit) else scenarios
        val stream = DeviceRunEvidence.DurableTurnStream(paths)
        var turnsRun = 0
        var leakage = 0
        var observationDisagreements = 0
        var crashed = true
        val seenKeys = linkedSetOf<String>()
        val duplicateKeys = mutableListOf<String>()
        val depthCounts = sortedMapOf<Int, Int>()
        val kinds = linkedSetOf<String>()
        val latencies = mutableListOf<Long>()

        try {
            runBlocking {
                selected.forEachIndexed { position, scenario ->
                    kernel.resetSession()
                    val opening = store.getOrCreate()
                    if (opening.conversationMemory.selectedContact != null ||
                        opening.transcript.isNotEmpty()
                    ) {
                        leakage++
                    }
                    kinds += scenario.kind

                    scenario.turns.forEach { turn ->
                        val question = turn.question.trim()
                        val session = store.getOrCreate()
                        val matches = contactDirectory.resolve(
                            ContactNameCandidates.candidates(question),
                        )
                        val observedAct = DeterministicTurnRouter.act(
                            session.turnContext(question, catalog.contractsByModelName.keys, null, matches),
                        )

                        tracer.clear()
                        val before = counters.snapshot()
                        val began = System.currentTimeMillis()
                        val events = kernel.runTurn(turn.question).toList()
                        val elapsed = System.currentTimeMillis() - began
                        val delta = counters.snapshot() - before
                        latencies += elapsed
                        turnsRun++

                        val kernelAct = kernel.diagnostics.last?.dialogueAct
                        if (kernelAct != null && kernelAct != observedAct.name) observationDisagreements++

                        val answer = events.filterIsInstance<AgentEvent.FinalMessage>().lastOrNull()?.text
                            ?: events.filterIsInstance<AgentEvent.UserError>().lastOrNull()?.messageKo.orEmpty()

                        val after = store.getOrCreate()
                        val action = after.conversationMemory.actions.lastOrNull()
                        val tools = action?.executedTools.orEmpty()

                        val key = "${scenario.index}/${turn.depth}"
                        if (!seenKeys.add(key)) duplicateKeys += key
                        depthCounts[turn.depth] = (depthCounts[turn.depth] ?: 0) + 1

                        // Written, flushed and synced now — not accumulated for the end. A run that
                        // dies at turn 300 leaves 300 turns behind.
                        stream.append(
                            rawTurn(
                                scenario, turn, observedAct.name, kernelAct, tracer.lastRanking,
                                after.conversationMemory.selectedContact?.cardId, tools,
                                matches.map { it.name }, action?.outcomeType?.name, elapsed, answer,
                                delta.asLongMap(),
                            ),
                        )
                        DeviceRunEvidence.replaceAtomically(
                            paths.progress,
                            """{"run_id":${q(mode.runId)},"mode":${q(mode.name)},""" +
                                """"turns_written":${stream.written},""" +
                                """"scenarios_done":$position,"expected_turns":${selected.sumOf { it.turns.size }}}""",
                        )
                    }
                }
            }
            crashed = false
        } finally {
            val elapsedTotal = System.currentTimeMillis() - startedAt
            val snapshot = counters.snapshot()
            val expectedTurns = selected.sumOf { it.turns.size }
            val gate = DeviceRunEvidence.RunInventoryGate.check(
                DeviceRunEvidence.RunInventoryGate.Expected(
                    EXPECTED_SCENARIOS, EXPECTED_TURNS, EXPECTED_KINDS, EXPECTED_DEPTHS,
                ),
                DeviceRunEvidence.RunInventoryGate.Observed(
                    scenarios = selected.size,
                    turns = turnsRun,
                    kinds = kinds.size,
                    depthCounts = depthCounts,
                    duplicateTurnKeys = duplicateKeys,
                    missingTurnKeys = if (turnsRun < expectedTurns) listOf("$turnsRun/$expectedTurns") else emptyList(),
                    crashed = crashed,
                ),
                mode,
            )
            val promotionFailures = stream.promote(gate)
            val validity = validityFailures(
                snapshot, leakage, observationDisagreements, promotionFailures, paths,
            )

            // The status is written whether or not the run finished; an incomplete run's status says
            // so, and its partial is still on disk next to it.
            DeviceRunEvidence.writeDurably(
                paths.status,
                statusJson(startedAt, elapsedTotal, turnsRun, expectedTurns, selected.size,
                    crashed, promotionFailures, snapshot, leakage, observationDisagreements, paths),
            )
            if (promotionFailures.isEmpty()) {
                DeviceRunEvidence.writeDurably(
                    paths.result,
                    resultJson(startedAt, elapsedTotal, scenarioSha, cardSha, cards.size,
                        selected.size, turnsRun, kinds.size, depthCounts, snapshot, retrievalModes,
                        leakage, observationDisagreements, latencies, modelFile, deployment,
                        catalog.contractsByModelName.keys.sorted(), validity, paths),
                )
            }
            android.util.Log.i(TAG, "wrote ${paths.directory.absolutePath} turns=$turnsRun " +
                "promotion=${promotionFailures.size} validity=${validity.size}")
            gateway.close()
            database.close()

            return Outcome(
                turnsRun, selected.size, promotionFailures, observationDisagreements,
                leakage, snapshot, validity,
            )
        }
    }

    // ---- validity ---------------------------------------------------------------------------------

    /**
     * Every reason this run may not be quoted as a measurement.
     *
     * Kept separate from the process exit code on purpose: an instrumentation run that finishes
     * without throwing has told you the process did not crash, and nothing else.
     */
    private fun validityFailures(
        counters: com.hjp.agent.contract.RuntimeCounterSnapshot,
        leakage: Int,
        disagreements: Int,
        promotionFailures: List<String>,
        paths: DeviceRunEvidence.Paths,
    ): List<String> = buildList {
        if (leakage > 0) add("CROSS_SCENARIO_LEAKAGE: $leakage")
        if (disagreements > 0) add("OBSERVATION_DISAGREEMENT: $disagreements")
        promotionFailures.forEach { add("PROMOTION: $it") }
        if (mode == RunMode.OFFICIAL) {
            if (DeviceRunEvidence.OfficialRunGuard.invocationCount(paths) != 1) {
                add("INVOCATION_COUNT: ${DeviceRunEvidence.OfficialRunGuard.invocationCount(paths)}")
            }
            // The whole purpose of RUN_D4 is the model. Zero successful invocations means the thing
            // being measured did not run, which is a failed measurement rather than a clean one.
            if (!counters.actualModelExecuted) {
                add("ACTUAL_MODEL_NEVER_RAN: ${counters.modelInvocationSuccesses} successes")
            }
            if (counters.modelLoadSuccesses == 0L) {
                add("MODEL_NEVER_LOADED: ${counters.modelLoadAttempts} attempts")
            }
        }
        if (counters.modelInvocationSuccesses > counters.modelInvocationAttempts) {
            add("MODEL_SUCCESSES_EXCEED_ATTEMPTS")
        }
        if (counters.modelLatencySamples != counters.modelInvocationSuccesses) {
            add("MODEL_LATENCY_SAMPLE_MISMATCH")
        }
        if (counters.turnsStarted != counters.deterministicOnlyTurns + counters.modelBoundaryTurns) {
            add("TURN_CLASSIFICATION_UNBALANCED")
        }
    }

    // ---- serialisation -----------------------------------------------------------------------------

    private fun invocationMarker(
        startedAt: Long, scenarioSha: String, cardSha: String, modelFile: File,
        deployment: com.hjp.agent.core.ModelDeployment,
    ): String = buildString {
        append("{\n")
        append("""  "schema": ${q(DeviceRunEvidence.SCHEMA)},""").append('\n')
        append("""  "run_id": ${q(mode.runId)},""").append('\n')
        append("""  "mode": ${q(mode.name)},""").append('\n')
        append("""  "invocation_ordinal": 1,""").append('\n')
        append("""  "started_utc": ${q(iso(startedAt))},""").append('\n')
        append("""  "git_head": ${q(BuildConfig.BUILD_TYPE)},""").append('\n')
        append("""  "dataset_sha256": ${q(scenarioSha)},""").append('\n')
        append("""  "fixture_sha256": ${q(cardSha)},""").append('\n')
        append("""  "expected_dataset_sha256": ${q(FROZEN_SCENARIO_SHA256)},""").append('\n')
        append("""  "expected_fixture_sha256": ${q(FROZEN_CARDS_SHA256)},""").append('\n')
        append("""  "model_path": ${q(modelFile.absolutePath)},""").append('\n')
        append("""  "model_bytes": ${modelFile.length()},""").append('\n')
        append("""  "model_deployment": ${jsonMap(deployment.diagnosticSummary())},""").append('\n')
        append("""  "device": {"manufacturer": ${q(Build.MANUFACTURER)}, "model": ${q(Build.MODEL)}, """)
        append(""""sdk": ${Build.VERSION.SDK_INT}, "abis": ${jsonArr(Build.SUPPORTED_ABIS.toList())}, """)
        append(""""fingerprint": ${q(Build.FINGERPRINT)}},""").append('\n')
        append("""  "process_pid": ${android.os.Process.myPid()}""").append('\n')
        append("}\n")
    }

    private fun rawTurn(
        scenario: DeviceScenario, turn: DeviceTurn, observedAct: String, kernelAct: String?,
        ranking: List<String>, focus: String?, tools: List<String>, directoryNames: List<String>,
        outcome: String?, elapsed: Long, answer: String, counterDelta: Map<String, Long>,
    ): String = buildString {
        append("{")
        append(""""schema":${q("ryeong_v4_raw_turn/v1")},""")
        append(""""run_id":${q(mode.runId)},"mode":${q(mode.name)},""")
        append(""""scenario":${scenario.index},"depth":${turn.depth},""")
        append(""""turn_count":${scenario.turns.size},"kind":${q(scenario.kind)},""")
        append(""""question":${q(turn.question)},""")
        append(""""expected_route":${turn.expectedRoute?.let { q(it) } ?: "null"},""")
        append(""""observed_act":${q(observedAct)},""")
        append(""""kernel_dialogue_act":${kernelAct?.let { q(it) } ?: "null"},""")
        append(""""outcome":${outcome?.let { q(it) } ?: "null"},""")
        append(""""gold":${jsonArr(turn.goldCardIds)},""")
        append(""""ranking":${jsonArr(ranking)},"ranking_size":${ranking.size},""")
        append(""""ranking_source":${q(if (ranking.isEmpty()) "NONE" else "SEARCH")},""")
        append(""""selected_card_id":${focus?.let { q(it) } ?: "null"},""")
        append(""""directory_matches":${jsonArr(directoryNames)},""")
        append(""""tools":${jsonArr(tools)},""")
        append(""""action_tools":${jsonArr(tools.filter { it in ACTION_TOOLS })},""")
        append(""""latency_millis":$elapsed,""")
        append(""""answer":${q(answer)},""")
        append(""""counters":{${counterDelta.entries.joinToString(",") { "${q(it.key)}:${it.value}" }}},""")
        append(""""threw":null""")
        append("}")
    }

    private fun statusJson(
        startedAt: Long, elapsed: Long, turnsRun: Int, expectedTurns: Int, scenarios: Int,
        crashed: Boolean, promotionFailures: List<String>,
        counters: com.hjp.agent.contract.RuntimeCounterSnapshot,
        leakage: Int, disagreements: Int, paths: DeviceRunEvidence.Paths,
    ): String = buildString {
        append("{\n")
        append("""  "schema": ${q("ryeong_v4_device_run_status/v1")},""").append('\n')
        append("""  "run_id": ${q(mode.runId)},""").append('\n')
        append("""  "mode": ${q(mode.name)},""").append('\n')
        append("""  "invocations": ${DeviceRunEvidence.OfficialRunGuard.invocationCount(paths)},""").append('\n')
        append("""  "started_utc": ${q(iso(startedAt))},""").append('\n')
        append("""  "elapsed_millis": $elapsed,""").append('\n')
        append("""  "scenarios": $scenarios,""").append('\n')
        append("""  "completed_turns": $turnsRun,""").append('\n')
        append("""  "expected_turns": $expectedTurns,""").append('\n')
        append("""  "completed": ${!crashed && promotionFailures.isEmpty()},""").append('\n')
        append("""  "partial": ${promotionFailures.isNotEmpty()},""").append('\n')
        append("""  "promotion_failures": ${jsonArr(promotionFailures)},""").append('\n')
        append("""  "partial_files_remaining": ${jsonArr(paths.stalePartials())},""").append('\n')
        append("""  "actual_model_executed": ${counters.actualModelExecuted},""").append('\n')
        append("""  "actual_model_invocation_successes": ${counters.modelInvocationSuccesses},""").append('\n')
        append("""  "actual_semantic_executed": ${counters.actualSemanticExecuted},""").append('\n')
        append("""  "semantic_invocations": ${counters.semanticInvocations},""").append('\n')
        append("""  "cross_scenario_leakage": $leakage,""").append('\n')
        append("""  "observation_disagreements": $disagreements""").append('\n')
        append("}\n")
    }

    private fun resultJson(
        startedAt: Long, elapsed: Long, scenarioSha: String, cardSha: String, cardCount: Int,
        scenarios: Int, turnsRun: Int, kinds: Int, depthCounts: Map<Int, Int>,
        counters: com.hjp.agent.contract.RuntimeCounterSnapshot, retrievalModes: Set<String>,
        leakage: Int, disagreements: Int, latencies: List<Long>, modelFile: File,
        deployment: com.hjp.agent.core.ModelDeployment, toolCatalog: List<String>,
        validity: List<String>, paths: DeviceRunEvidence.Paths,
    ): String {
        val sorted = latencies.sorted()
        fun pct(p: Double) = if (sorted.isEmpty()) 0L else
            sorted[minOf(sorted.size - 1, maxOf(0, Math.round(p * (sorted.size - 1)).toInt()))]
        val rawSha = if (paths.rawTurns.isFile) sha256(paths.rawTurns.readBytes()) else ""
        return buildString {
            append("{\n")
            append("""  "schema": ${q("ryeong_v4_device_result/v1")},""").append('\n')
            append("""  "run_id": ${q(mode.runId)},""").append('\n')
            append("""  "mode": ${q(mode.name)},""").append('\n')
            append("""  "invocations": ${DeviceRunEvidence.OfficialRunGuard.invocationCount(paths)},""").append('\n')
            append("""  "axis": ${q("search / focus / follow-up compatibility on device against the actual model — NOT a whole-agent tool score")},""").append('\n')
            append("""  "started_utc": ${q(iso(startedAt))}, "elapsed_millis": $elapsed,""").append('\n')
            append("""  "dataset": {"asset": ${q(SCENARIO_ASSET)}, "sha256": ${q(scenarioSha)}},""").append('\n')
            append("""  "fixture": {"asset": ${q(CARD_ASSET)}, "sha256": ${q(cardSha)}, "count": $cardCount},""").append('\n')
            append("""  "raw_turns_sha256": ${q(rawSha)},""").append('\n')
            append("""  "inventory": {"scenarios": $scenarios, "turns": $turnsRun, "kinds": $kinds, """)
            append(""""depths": {${depthCounts.entries.joinToString(",") { "${q(it.key.toString())}:${it.value}" }}}},""").append('\n')
            append("""  "assembly": {"engine_mode": "REACT", "contact_directory": "RepositoryContactDirectory", """)
            append(""""tool_catalog": ${jsonArr(toolCatalog)}},""").append('\n')
            append("""  "device": {"manufacturer": ${q(Build.MANUFACTURER)}, "model": ${q(Build.MODEL)}, """)
            append(""""sdk": ${Build.VERSION.SDK_INT}, "abis": ${jsonArr(Build.SUPPORTED_ABIS.toList())}, """)
            append(""""fingerprint": ${q(Build.FINGERPRINT)}, "emulator": false},""").append('\n')
            append("""  "model": {"path": ${q(modelFile.absolutePath)}, "bytes": ${modelFile.length()}, """)
            append(""""deployment": ${jsonMap(deployment.diagnosticSummary())}},""").append('\n')
            append("""  "runtime_counters": {""")
            append(counters.asLongMap().entries.joinToString(",") { "${q(it.key)}:${it.value}" })
            append("},\n")
            append("""  "actual_model_executed": ${counters.actualModelExecuted},""").append('\n')
            append("""  "actual_model_executed_derived_from": ${q("AgentRuntimeCounters.modelInvocationSuccesses")},""").append('\n')
            append("""  "actual_semantic_executed": ${counters.actualSemanticExecuted},""").append('\n')
            append("""  "retrieval_modes": ${jsonArr(retrievalModes.toList())},""").append('\n')
            append("""  "cross_scenario_leakage": $leakage,""").append('\n')
            append("""  "observation_disagreements": $disagreements,""").append('\n')
            append("""  "turn_latency": {"p50": ${pct(0.5)}, "p95": ${pct(0.95)}, "max": ${sorted.lastOrNull() ?: 0}, """)
            append(""""samples": ${sorted.size}, "note": "wall time per turn; it mixes deterministic and model turns and is not a model latency"},""").append('\n')
            append("""  "model_latency": {"samples": ${counters.modelLatencySamples}, """)
            append(""""total_millis": ${counters.modelLatencyTotalMillis}, "max_millis": ${counters.modelLatencyMaxMillis}},""").append('\n')
            append("""  "thermal_battery": ${q("collected by the host in PHASE_B; correlate by run_id and started_utc")},""").append('\n')
            append("""  "validity_failures": ${jsonArr(validity)},""").append('\n')
            append("""  "validity_verdict": ${q(if (validity.isEmpty()) "VALID BASELINE" else "INVALID RUN")},""").append('\n')
            append("""  "exit_code_is_not_validity": ${q("a process that did not crash has said nothing about whether this run may be quoted")}""").append('\n')
            append("}\n")
        }
    }

    // ---- read-only observation ---------------------------------------------------------------------

    private class TracingBackend(private val delegate: ContactSearchBackend) : ContactSearchBackend {
        var lastRanking: List<String> = emptyList()
            private set

        override suspend fun search(query: String, limit: Int): ContactSearchResponse {
            val response = delegate.search(query, limit)
            lastRanking = response.hits.map { it.card.id }
            return response
        }

        override suspend fun get(cardId: String): BusinessCardRecord? = delegate.get(cardId)
        override fun engineName(): String = delegate.engineName()
        override fun configurationAvailable(): Boolean = delegate.configurationAvailable()
        fun clear() { lastRanking = emptyList() }
    }

    private class RecordingCalendarBackend : CalendarComposerBackend {
        val drafts = mutableListOf<CalendarDraft>()
        override fun isAvailable(): Boolean = true
        override suspend fun open(draft: CalendarDraft): Boolean { drafts += draft; return true }
    }

    private class RecordingMessageBackend : MessageComposerBackend {
        val drafts = mutableListOf<MessageDraft>()
        override fun isAvailable(channel: MessageChannel?): Boolean = true
        override suspend fun open(draft: MessageDraft): Boolean { drafts += draft; return true }
    }

    private class EvaluationEnvironment : AgentRuntimeEnvironment {
        override val localeTag = "ko-KR"
        override val timeZoneId = "Asia/Seoul"
        override suspend fun grantedPermissions(): Set<String> = emptySet()
        override suspend fun deviceCapabilities(): Set<String> = setOf(
            "android.external_ui", "contact.local_search", "contact.local_update", "datetime.current",
        )
        override suspend fun toolContext(sessionId: String, turnId: String) =
            ToolExecutionContext(sessionId, turnId, "ko-KR", "Asia/Seoul")
    }

    // ---- frozen input parsing -------------------------------------------------------------------

    data class DeviceTurn(
        val depth: Int, val question: String, val expectedRoute: String?, val goldCardIds: List<String>,
    )

    data class DeviceScenario(val index: Int, val kind: String, val turns: List<DeviceTurn>)

    private fun parseScenarios(raw: String): List<DeviceScenario> {
        val root = Json.parseToJsonElement(raw) as JsonObject
        return (root["scenarios"] as JsonArray).map { element ->
            val scenario = element as JsonObject
            DeviceScenario(
                index = (scenario["index"] as JsonPrimitive).content.toInt(),
                kind = (scenario["kind"] as JsonPrimitive).content,
                turns = (scenario["turns"] as JsonArray).map { turnElement ->
                    val turn = turnElement as JsonObject
                    val route = turn["expected_route"]
                    DeviceTurn(
                        depth = (turn["depth"] as JsonPrimitive).content.toInt(),
                        question = (turn["question"] as JsonPrimitive).content,
                        expectedRoute = if (route == null || route is JsonNull) null
                        else (route as JsonPrimitive).content,
                        goldCardIds = (turn["gold_card_ids"] as JsonArray)
                            .map { (it as JsonPrimitive).content },
                    )
                },
            )
        }
    }

    private fun parseCards(raw: String): List<BusinessCardRecord> =
        (Json.parseToJsonElement(raw) as JsonArray).map { element ->
            val card = element as JsonObject
            fun s(key: String) = (card[key] as? JsonPrimitive)?.content.orEmpty()
            BusinessCardRecord(
                id = s("id"), name = s("name"), nameEn = s("nameEn"), company = s("company"),
                title = s("title"), department = s("department"), industry = s("industry"),
                location = s("location"), phone = s("phone"), email = s("email"),
                address = s("address"), memo = s("memo"),
            )
        }

    private fun BusinessCardRecord.toEntity() = BusinessCardEntity(
        id = id, name = name, nameEn = nameEn, company = company, title = title,
        department = department, industry = industry, location = location, phone = phone,
        mobile = "", email = email, address = address, website = "", memo = memo,
        tagsJson = "[]", updatedAt = "",
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun iso(epochMillis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(epochMillis))

    private fun q(value: String): String = "\"" + value
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun jsonArr(values: List<String>) = values.joinToString(",", "[", "]") { q(it) }

    private fun jsonMap(values: Map<String, String>) =
        values.entries.joinToString(",", "{", "}") { "${q(it.key)}:${q(it.value)}" }

    companion object {
        const val TAG = "RyeongDeviceEvalV4"

        const val SCENARIO_ASSET = "ryeong/scenarios_v2.json"
        const val CARD_ASSET = "ryeong/cards_eval1000.json"

        const val FROZEN_SCENARIO_SHA256 =
            "2d86ce0a1ca8be195554cb0292f48a0fb1d39b892f88a51295caff7943cdf685"
        const val FROZEN_CARDS_SHA256 =
            "f0feaebfdf5eb26c2a161a4b8c40d1307a6f5fa9c68f00309f05b69d03e7cd24"

        const val EXPECTED_SCENARIOS = 139
        const val EXPECTED_TURNS = 386
        const val EXPECTED_KINDS = 22
        const val EXPECTED_CARDS = 1000

        /**
         * How many *turns* sit at each depth in the frozen set, summing to [EXPECTED_TURNS].
         *
         * Not to be confused with `RyeongV2ScenarioLoader.EXPECTED_DEPTH`, which counts *scenarios*
         * by their length and sums to 139. Two different inventories over the same file; comparing
         * a run against the wrong one would pass every run and prove nothing.
         */
        val EXPECTED_DEPTHS: Map<Int, Int> =
            mapOf(1 to 139, 2 to 127, 3 to 65, 4 to 35, 5 to 10, 6 to 10)

        val ACTION_TOOLS = setOf("open_compose", "create_calendar_event", "update_business_card")

        val PRODUCTION_TOOLS = sortedSetOf(
            "create_calendar_event", "get_contact", "get_current_datetime",
            "open_compose", "search_contacts", "update_business_card",
        )

        const val SYSTEM_INSTRUCTION = AppContainer.SYSTEM_INSTRUCTION
    }
}
