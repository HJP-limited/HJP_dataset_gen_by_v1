package com.example.hjp

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.example.hjp.deviceeval.DeviceRunEvidenceV5
import com.example.hjp.deviceeval.DeviceRunEvidenceV5.RunMode
import com.example.hjp.deviceeval.V5ContactDependencyRule
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The v5 device evaluation body, shared by the smoke class and the official class.
 *
 * ## Why this is not the v4 runner with a flag
 *
 * `RyeongDeviceRunnerV4` is pinned in the v4 freeze and is the runner RUN_D4 would have used. It is
 * left exactly as it is. More to the point, it could not do this job: it records tool *names* and not
 * their arguments, which is precisely why the frozen v4 device scorer had to decide whether an action
 * was safe from `expected_route` — a label on the dataset — and could not see a wrong recipient at
 * all.
 *
 * So the v5 runner records what the v5 rule needs:
 *
 *  - every dispatched call **with its arguments**, in execution order;
 *  - the session generation, read from the live session rather than assumed;
 *  - the contact in focus *before* the turn, for the leakage checks;
 *  - the detail reads this turn performed, with the target epoch each ran in;
 *  - the per-argument provenance, and the contact-dependency findings that follow from it.
 *
 * The judgement is [V5ContactDependencyRule], the same code the host evaluator runs, so a device
 * record and a host score cannot disagree because two people wrote the rule twice.
 *
 * Everything below the boundary is the production class: the real kernel, the real router, the real
 * session and memory, the real contact directory over a real Room database, the real Ryeong search
 * service, the real EmbeddingGemma engine and the real LiteRT gateway loading the real `.litertlm`.
 *
 * Two things are deliberately not real, and neither can change a routing or retrieval decision: the
 * calendar and message backends are recording fakes, so nothing is sent or saved on somebody's
 * device; and the card database is in-memory, so the evaluation fixture never lands in app storage.
 *
 * Nothing in this file runs unless an instrumentation argument asks for it.
 */
class RyeongDeviceRunnerV5(
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
        val contactDependencyFindings: Int,
        val contactConsumingArguments: Int,
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun run(): Outcome {
        // A capped official run would produce a short result carrying the official identifier, which
        // no later reader could tell from a complete one. Refused before anything else happens.
        DeviceRunEvidenceV5.requireScenarioLimitAllowed(mode, scenarioLimit)

        val startedAt = System.currentTimeMillis()
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val paths = DeviceRunEvidenceV5.Paths(root, mode).prepare()

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

        val cardsById = cards.associateBy { it.id }
        val contactStore = V5ContactDependencyRule.Store.of(
            cards.map { V5ContactDependencyRule.Card(it.id, it.email, it.phone, it.mobile) },
        )
        val contactContract = V5ContactDependencyRule.PRODUCTION_CONTRACT

        // ---- the official invocation, claimed atomically -----------------------------------------
        val markerText = invocationMarker(startedAt, scenarioSha, cardSha, modelFile, deployment)
        if (mode.countsAsOfficialInvocation) {
            when (
                val claim =
                    DeviceRunEvidenceV5.OfficialRunGuard.claim(paths, markerText, iso(startedAt))
            ) {
                is DeviceRunEvidenceV5.ClaimResult.Refused ->
                    error("RUN_D5 refused: ${claim.code} — ${claim.detail}")
                is DeviceRunEvidenceV5.ClaimResult.Claimed -> Unit
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

        // The one v5 addition below the boundary, and it is an observer: the delegate is the
        // production executor, every call is forwarded untouched, and the return value is the
        // production result. Without it a device record carries tool names and no arguments, which
        // is exactly the gap that made the v4 device scorer fall back on the dataset's route label.
        val executed = mutableListOf<Pair<String, JsonObject>>()
        val recordingExecutor = object : com.hjp.agent.core.ToolExecutor {
            private val delegate = DefaultToolExecutor(registry)
            override suspend fun execute(
                call: com.hjp.agent.contract.ModelToolCall,
                snapshot: com.hjp.tool.contract.ToolCatalogSnapshot,
                context: ToolExecutionContext,
            ): com.hjp.tool.contract.ToolExecutionResult {
                val result = delegate.execute(call, snapshot, context)
                if (result is com.hjp.tool.contract.ToolExecutionResult.Success) {
                    executed += call.modelToolName to call.arguments
                }
                return result
            }
        }

        val store = InMemoryAgentSessionStore()
        val gateway = CountingAgentModelGateway(
            LiteRtAgentModelGateway(
                modelFile,
                File(context.cacheDir, "litertlm-ryeong-eval-v5"),
                LiteRtBackendPreference.CPU_ONLY,
                counters = counters,
            ),
            counters,
        )
        val environment = EvaluationEnvironment()
        val sessionManager = AgentSessionManager(store, gateway, SYSTEM_INSTRUCTION, "ko-KR")
        val kernel = AgentKernel(
            registry = registry,
            toolExecutor = recordingExecutor,
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
        // The rule cannot judge a tool it has never heard of, and a tool with no declared contact
        // role would be scored as if it had none. Checked against the live catalog, not a copy.
        val classified = contactContract.actionRules.keys + contactContract.rankingTools +
            contactContract.verificationTool + "get_current_datetime"
        check(catalog.contractsByModelName.keys.all { it in classified }) {
            "a production tool has no contact-dependency role: " +
                "${catalog.contractsByModelName.keys - classified}"
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
        val stream = DeviceRunEvidenceV5.DurableTurnStream(paths)
        var turnsRun = 0
        var leakage = 0
        var observationDisagreements = 0
        var dependencyFindings = 0
        var consumingArguments = 0
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

                    var previousFocusCardId: String? = null
                    val priorVerifications = mutableListOf<V5ContactDependencyRule.Verification>()

                    scenario.turns.forEach { turn ->
                        val question = turn.question.trim()
                        val session = store.getOrCreate()
                        val generation = session.generation
                        val matches = contactDirectory.resolve(
                            ContactNameCandidates.candidates(question),
                        )
                        val observedAct = DeterministicTurnRouter.act(
                            session.turnContext(
                                question, catalog.contractsByModelName.keys, null, matches,
                            ),
                        )

                        tracer.clear()
                        executed.clear()
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
                        val tools = executed.map { it.first }
                        val calls = executed.map { (tool, arguments) ->
                            V5ContactDependencyRule.Call(tool, flatten(arguments, ""))
                        }
                        val key = "${scenario.index}/${turn.depth}"

                        val provenance = V5ContactDependencyRule.judge(
                            calls = calls,
                            question = question,
                            previousFocusCardId = previousFocusCardId,
                            sessionGeneration = generation,
                            turnKey = key,
                            store = contactStore,
                            contract = contactContract,
                            priorVerifications = priorVerifications.toList(),
                        )
                        consumingArguments += provenance.consuming.size
                        dependencyFindings += provenance.unverified.size
                        priorVerifications += provenance.verificationsThisTurn

                        val previousCard = previousFocusCardId?.let { cardsById[it] }
                        val actionArguments = executed
                            .filter { it.first in ACTION_TOOLS }
                            .joinToString(" ") { it.second.toString() }
                        val leakedIntoArguments = leakedFields(previousCard, actionArguments)
                        val unverifiedTargets = unverifiedTargets(
                            executed, tracer.lastRanking, cardsById,
                        )

                        if (!seenKeys.add(key)) duplicateKeys += key
                        depthCounts[turn.depth] = (depthCounts[turn.depth] ?: 0) + 1

                        // Written, flushed and synced now — not accumulated for the end. A run that
                        // dies at turn 300 leaves 300 turns behind.
                        stream.append(
                            rawTurn(
                                scenario, turn, observedAct.name, kernelAct, tracer.lastRanking,
                                after.conversationMemory.selectedContact?.cardId,
                                after.conversationMemory.selectedContact?.name,
                                tools, executed.toList(), matches.map { it.name },
                                action?.outcomeType?.name, elapsed, answer, delta.asLongMap(),
                                generation, previousFocusCardId, provenance,
                                unverifiedTargets, leakedIntoArguments,
                            ),
                        )
                        DeviceRunEvidenceV5.replaceAtomically(
                            paths.progress,
                            """{"run_id":${q(mode.runId)},"mode":${q(mode.name)},""" +
                                """"turns_written":${stream.written},""" +
                                """"scenarios_done":$position,""" +
                                """"expected_turns":${selected.sumOf { it.turns.size }}}""",
                        )
                        previousFocusCardId = after.conversationMemory.selectedContact?.cardId
                    }
                }
            }
            crashed = false
        } finally {
            val elapsedTotal = System.currentTimeMillis() - startedAt
            val snapshot = counters.snapshot()
            val expectedTurns = selected.sumOf { it.turns.size }
            val gate = DeviceRunEvidenceV5.RunInventoryGate.check(
                DeviceRunEvidenceV5.RunInventoryGate.Expected(
                    EXPECTED_SCENARIOS, EXPECTED_TURNS, EXPECTED_KINDS, EXPECTED_DEPTHS,
                ),
                DeviceRunEvidenceV5.RunInventoryGate.Observed(
                    scenarios = selected.size,
                    turns = turnsRun,
                    kinds = kinds.size,
                    depthCounts = depthCounts,
                    duplicateTurnKeys = duplicateKeys,
                    missingTurnKeys = if (turnsRun < expectedTurns) {
                        listOf("$turnsRun/$expectedTurns")
                    } else {
                        emptyList()
                    },
                    crashed = crashed,
                ),
                mode,
            )
            val promotionFailures = stream.promote(gate)
            val validity = validityFailures(
                snapshot, leakage, observationDisagreements, dependencyFindings,
                promotionFailures, paths,
            )

            DeviceRunEvidenceV5.writeDurably(
                paths.status,
                statusJson(startedAt, elapsedTotal, turnsRun, expectedTurns, selected.size,
                    crashed, promotionFailures, snapshot, leakage, observationDisagreements,
                    dependencyFindings, consumingArguments, paths),
            )
            if (promotionFailures.isEmpty()) {
                DeviceRunEvidenceV5.writeDurably(
                    paths.result,
                    resultJson(startedAt, elapsedTotal, scenarioSha, cardSha, cards.size,
                        selected.size, turnsRun, kinds.size, depthCounts, snapshot, retrievalModes,
                        leakage, observationDisagreements, dependencyFindings, consumingArguments,
                        latencies, modelFile, deployment,
                        catalog.contractsByModelName.keys.sorted(), validity, paths),
                )
            }
            android.util.Log.i(TAG, "wrote ${paths.directory.absolutePath} turns=$turnsRun " +
                "promotion=${promotionFailures.size} validity=${validity.size} " +
                "contact_findings=$dependencyFindings")
            gateway.close()
            database.close()

            return Outcome(
                turnsRun, selected.size, promotionFailures, observationDisagreements,
                leakage, snapshot, validity, dependencyFindings, consumingArguments,
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
    @Suppress("LongParameterList")
    private fun validityFailures(
        counters: com.hjp.agent.contract.RuntimeCounterSnapshot,
        leakage: Int,
        disagreements: Int,
        dependencyFindings: Int,
        promotionFailures: List<String>,
        paths: DeviceRunEvidenceV5.Paths,
    ): List<String> = buildList {
        if (leakage > 0) add("CROSS_SCENARIO_LEAKAGE: $leakage")
        if (disagreements > 0) add("OBSERVATION_DISAGREEMENT: $disagreements")
        if (dependencyFindings > 0) add("ACTION_WITHOUT_CONTACT_READ: $dependencyFindings")
        promotionFailures.forEach { add("PROMOTION: $it") }
        if (mode == RunMode.OFFICIAL) {
            val invocations = DeviceRunEvidenceV5.OfficialRunGuard.invocationCount(paths)
            if (invocations != 1) add("INVOCATION_COUNT: $invocations")
            // The whole purpose of RUN_D5 is the model. Zero successful invocations means the thing
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

    // ---- read-only derivations, restated from the v2 runner -----------------------------------------

    /** Fields of the previous contact that appear verbatim in an action's arguments. */
    private fun leakedFields(previous: BusinessCardRecord?, text: String): List<String> {
        if (previous == null) return emptyList()
        return linkedMapOf(
            "card_id" to previous.id,
            "name" to previous.name,
            "email" to previous.email,
            "phone" to previous.phone,
            "mobile" to previous.mobile,
            "company" to previous.company,
            "department" to previous.department,
            "address" to previous.address,
        ).filterValues { it.isNotBlank() && text.contains(it) }.keys.toList()
    }

    /**
     * Side-effect arguments naming somebody this turn did not surface.
     *
     * A separate observation from the contact-dependency finding, and deliberately so: this one asks
     * whether the value belongs to a card the turn *saw at all*, and the v5 rule asks whether the
     * exact card was *read in detail*. A trace can fail one and pass the other.
     */
    private fun unverifiedTargets(
        calls: List<Pair<String, JsonObject>>,
        surfacedCardIds: List<String>,
        cardsById: Map<String, BusinessCardRecord>,
    ): List<String> {
        val allowed = buildSet {
            surfacedCardIds.mapNotNull { cardsById[it] }.forEach { card ->
                listOf(card.email, card.phone, card.mobile).filter(String::isNotBlank).forEach(::add)
                add(card.id)
            }
        }
        return calls.filter { it.first in ACTION_TOOLS }.flatMap { (tool, arguments) ->
            CONTACT_VALUE.findAll(arguments.toString())
                .map { it.value }
                .filter { it !in allowed }
                .map { "$tool:$it" }
                .toList()
        }
    }

    /** Every string in the object, with its dotted / indexed path, depth first. */
    private fun flatten(element: JsonElement, prefix: String): List<Pair<String, String>> =
        when (element) {
            is JsonObject -> element.entries.flatMap { (key, value) ->
                flatten(value, if (prefix.isEmpty()) key else "$prefix.$key")
            }
            is JsonArray -> element.flatMapIndexed { index, value ->
                flatten(value, "$prefix[$index]")
            }
            is JsonPrimitive -> listOf(prefix to element.content)
            else -> emptyList()
        }

    // ---- serialisation -----------------------------------------------------------------------------

    private fun invocationMarker(
        startedAt: Long, scenarioSha: String, cardSha: String, modelFile: File,
        deployment: com.hjp.agent.core.ModelDeployment,
    ): String = buildString {
        append("{\n")
        append("""  "schema": ${q(DeviceRunEvidenceV5.SCHEMA)},""").append('\n')
        append("""  "run_id": ${q(mode.runId)},""").append('\n')
        append("""  "mode": ${q(mode.name)},""").append('\n')
        append("""  "invocation_ordinal": 1,""").append('\n')
        append("""  "started_utc": ${q(iso(startedAt))},""").append('\n')
        append("""  "git_head": ${q(BuildConfig.BUILD_TYPE)},""").append('\n')
        append("""  "dataset_sha256": ${q(scenarioSha)},""").append('\n')
        append("""  "fixture_sha256": ${q(cardSha)},""").append('\n')
        append("""  "expected_dataset_sha256": ${q(FROZEN_SCENARIO_SHA256)},""").append('\n')
        append("""  "expected_fixture_sha256": ${q(FROZEN_CARDS_SHA256)},""").append('\n')
        append("""  "contact_dependency_rule": ${q(V5ContactDependencyRule.VERSION)},""").append('\n')
        append("""  "model_path": ${q(modelFile.absolutePath)},""").append('\n')
        append("""  "model_bytes": ${modelFile.length()},""").append('\n')
        append("""  "model_deployment": ${jsonMap(deployment.diagnosticSummary())},""").append('\n')
        append("""  "device": {"manufacturer": ${q(Build.MANUFACTURER)}, "model": ${q(Build.MODEL)}, """)
        append(""""sdk": ${Build.VERSION.SDK_INT}, "abis": ${jsonArr(Build.SUPPORTED_ABIS.toList())}, """)
        append(""""fingerprint": ${q(Build.FINGERPRINT)}},""").append('\n')
        append("""  "process_pid": ${android.os.Process.myPid()}""").append('\n')
        append("}\n")
    }

    @Suppress("LongParameterList")
    private fun rawTurn(
        scenario: DeviceScenario, turn: DeviceTurn, observedAct: String, kernelAct: String?,
        ranking: List<String>, focus: String?, focusName: String?, tools: List<String>,
        calls: List<Pair<String, JsonObject>>, directoryNames: List<String>,
        outcome: String?, elapsed: Long, answer: String, counterDelta: Map<String, Long>,
        sessionGeneration: Long, previousFocusCardId: String?,
        provenance: V5ContactDependencyRule.TurnProvenance,
        unverifiedTargets: List<String>, leakedIntoArguments: List<String>,
    ): String = buildString {
        append("{")
        append(""""schema":${q("ryeong_v5_raw_turn/v1")},""")
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
        append(""""no_cards_out_of_scope":${turn.goldCardIds.isEmpty()},""")
        append(""""selected_card_id":${focus?.let { q(it) } ?: "null"},""")
        append(""""selected_name":${focusName?.let { q(it) } ?: "null"},""")
        append(""""previous_focus_card_id":${previousFocusCardId?.let { q(it) } ?: "null"},""")
        append(""""directory_matches":${jsonArr(directoryNames)},""")
        append(""""resolved_names":${jsonArr(directoryNames)},""")
        append(""""plan_titles":[],"plan_locations":[],""")
        append(""""tools":${jsonArr(tools)},""")
        append(""""action_tools":${jsonArr(tools.filter { it in ACTION_TOOLS })},""")
        append(""""tool_arguments":[""")
        append(
            calls.joinToString(",") { (tool, arguments) ->
                """{"tool":${q(tool)},"arguments":$arguments}"""
            },
        )
        append("],")
        append(""""session_generation":$sessionGeneration,""")
        append(""""target_epochs":${provenance.targetEpochs},""")
        append(""""contact_verifications":[""")
        append(
            provenance.verificationsThisTurn.joinToString(",") {
                """{"card_id":${q(it.cardId)},"target_epoch":${it.targetEpoch},""" +
                    """"call_index":${it.callIndex},"session_generation":${it.sessionGeneration}}"""
            },
        )
        append("],")
        append(""""action_provenance":[""")
        append(provenance.entries.joinToString(",") { provenanceJson(it) })
        append("],")
        append(""""unverified_target_uses":${jsonArr(unverifiedTargets)},""")
        append(""""leaked_into_action_arguments":${jsonArr(leakedIntoArguments)},""")
        append(""""leaked_fields":${jsonArr(leakedIntoArguments)},""")
        append(""""v5_findings":[""")
        append(
            provenance.unverified.joinToString(",") { entry ->
                """{"code":${q("ACTION_WITHOUT_CONTACT_READ")},"detail":""" +
                    q(
                        "${entry.actionTool}.${entry.argumentPath} used " +
                            "${entry.sourceCardId ?: "a value no card in the store carries"} " +
                            "(${entry.fieldType}) with no verified get_contact in this turn: " +
                            "${entry.missingVerification}",
                    ) +
                    ""","provenance":${jsonArr(V5ContactDependencyRule.render(entry))}}"""
            },
        )
        append("],")
        append(""""latency_millis":$elapsed,""")
        append(""""answer":${q(answer)},""")
        append(""""counters":{${counterDelta.entries.joinToString(",") { "${q(it.key)}:${it.value}" }}},""")
        append(""""threw":null""")
        append("}")
    }

    /** A provenance entry, with a digest where the value would be. Never the address itself. */
    private fun provenanceJson(entry: V5ContactDependencyRule.Entry): String = buildString {
        append("{")
        append(""""action_tool":${q(entry.actionTool)},"call_index":${entry.callIndex},""")
        append(""""argument_json_path":${q(entry.argumentPath)},"declared":${entry.declared},""")
        append(""""kind":${entry.kind?.name?.let { q(it) } ?: "null"},""")
        append(""""source_type":${q(entry.sourceType.name)},""")
        append(""""source_card_id":${entry.sourceCardId?.let { q(it) } ?: "null"},""")
        append(""""source_card_digest":${q(entry.valueDigest)},""")
        append(""""field_type":${entry.fieldType?.let { q(it) } ?: "null"},""")
        append(""""explicit_user_input":${entry.explicitUserInput},""")
        append(""""contact_consuming":${entry.contactConsuming},"verified":${entry.verified},""")
        append(""""verification_tool":${entry.verificationTool?.let { q(it) } ?: "null"},""")
        append(""""verification_turn_key":${entry.verificationTurnKey?.let { q(it) } ?: "null"},""")
        append(""""verification_target_epoch":${entry.verificationTargetEpoch ?: "null"},""")
        append(""""action_target_epoch":${entry.actionTargetEpoch},""")
        append(""""session_generation":${entry.sessionGeneration},"stale":${entry.stale},""")
        append(""""missing_verification":${entry.missingVerification?.let { q(it) } ?: "null"}""")
        append("}")
    }

    @Suppress("LongParameterList")
    private fun statusJson(
        startedAt: Long, elapsed: Long, turnsRun: Int, expectedTurns: Int, scenarios: Int,
        crashed: Boolean, promotionFailures: List<String>,
        counters: com.hjp.agent.contract.RuntimeCounterSnapshot,
        leakage: Int, disagreements: Int, dependencyFindings: Int, consumingArguments: Int,
        paths: DeviceRunEvidenceV5.Paths,
    ): String = buildString {
        append("{\n")
        append("""  "schema": ${q("ryeong_v5_device_run_status/v1")},""").append('\n')
        append("""  "run_id": ${q(mode.runId)},""").append('\n')
        append("""  "mode": ${q(mode.name)},""").append('\n')
        append("""  "invocations": ${DeviceRunEvidenceV5.OfficialRunGuard.invocationCount(paths)},""").append('\n')
        append("""  "started_utc": ${q(iso(startedAt))},""").append('\n')
        append("""  "elapsed_millis": $elapsed,""").append('\n')
        append("""  "scenarios": $scenarios,""").append('\n')
        append("""  "completed_turns": $turnsRun,""").append('\n')
        append("""  "expected_turns": $expectedTurns,""").append('\n')
        append("""  "completed": ${!crashed && promotionFailures.isEmpty()},""").append('\n')
        append("""  "partial": ${promotionFailures.isNotEmpty()},""").append('\n')
        append("""  "promotion_failures": ${jsonArr(promotionFailures)},""").append('\n')
        append("""  "partial_files_remaining": ${jsonArr(paths.stalePartials())},""").append('\n')
        append("""  "raw_partial_files_remaining": ${paths.stalePartials().size},""").append('\n')
        append("""  "actual_model_executed": ${counters.actualModelExecuted},""").append('\n')
        append("""  "actual_model_invocation_successes": ${counters.modelInvocationSuccesses},""").append('\n')
        append("""  "actual_semantic_executed": ${counters.actualSemanticExecuted},""").append('\n')
        append("""  "semantic_invocations": ${counters.semanticInvocations},""").append('\n')
        append("""  "cross_scenario_leakage": $leakage,""").append('\n')
        append("""  "observation_disagreements": $disagreements,""").append('\n')
        append("""  "contact_consuming_arguments": $consumingArguments,""").append('\n')
        append("""  "contact_dependency_findings": $dependencyFindings""").append('\n')
        append("}\n")
    }

    @Suppress("LongParameterList")
    private fun resultJson(
        startedAt: Long, elapsed: Long, scenarioSha: String, cardSha: String, cardCount: Int,
        scenarios: Int, turnsRun: Int, kinds: Int, depthCounts: Map<Int, Int>,
        counters: com.hjp.agent.contract.RuntimeCounterSnapshot, retrievalModes: Set<String>,
        leakage: Int, disagreements: Int, dependencyFindings: Int, consumingArguments: Int,
        latencies: List<Long>, modelFile: File,
        deployment: com.hjp.agent.core.ModelDeployment, toolCatalog: List<String>,
        validity: List<String>, paths: DeviceRunEvidenceV5.Paths,
    ): String {
        val sorted = latencies.sorted()
        fun pct(p: Double) = if (sorted.isEmpty()) {
            0L
        } else {
            sorted[minOf(sorted.size - 1, maxOf(0, Math.round(p * (sorted.size - 1)).toInt()))]
        }
        val rawSha = if (paths.rawTurns.isFile) sha256(paths.rawTurns.readBytes()) else ""
        return buildString {
            append("{\n")
            append("""  "schema": ${q("ryeong_v5_device_result/v1")},""").append('\n')
            append("""  "run_id": ${q(mode.runId)},""").append('\n')
            append("""  "mode": ${q(mode.name)},""").append('\n')
            append("""  "invocations": ${DeviceRunEvidenceV5.OfficialRunGuard.invocationCount(paths)},""").append('\n')
            append("""  "axis": ${q("search / focus / follow-up compatibility on device against the actual model, plus the v5 per-argument contact-dependency finding — NOT a whole-agent tool score")},""").append('\n')
            append("""  "started_utc": ${q(iso(startedAt))}, "elapsed_millis": $elapsed,""").append('\n')
            append("""  "dataset": {"asset": ${q(SCENARIO_ASSET)}, "sha256": ${q(scenarioSha)}},""").append('\n')
            append("""  "fixture": {"asset": ${q(CARD_ASSET)}, "sha256": ${q(cardSha)}, "count": $cardCount},""").append('\n')
            append("""  "raw_turns_sha256": ${q(rawSha)},""").append('\n')
            append("""  "contact_dependency_rule": ${q(V5ContactDependencyRule.VERSION)},""").append('\n')
            append("""  "contact_dependency_findings": $dependencyFindings,""").append('\n')
            append("""  "contact_consuming_arguments": $consumingArguments,""").append('\n')
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
            append("""  "host_scoring_note": ${q("the host scorer recomputes every finding in this file from tool_arguments; the copies here are for comparison, not for quoting")},""").append('\n')
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
        val depth: Int, val question: String, val expectedRoute: String?,
        val goldCardIds: List<String>,
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
                        expectedRoute = if (route == null || route is JsonNull) {
                            null
                        } else {
                            (route as JsonPrimitive).content
                        },
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
        const val TAG = "RyeongDeviceEvalV5"

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
         * by their length and sums to 139.
         */
        val EXPECTED_DEPTHS: Map<Int, Int> =
            mapOf(1 to 139, 2 to 127, 3 to 65, 4 to 35, 5 to 10, 6 to 10)

        val ACTION_TOOLS = setOf("open_compose", "create_calendar_event", "update_business_card")

        val PRODUCTION_TOOLS = sortedSetOf(
            "create_calendar_event", "get_contact", "get_current_datetime",
            "open_compose", "search_contacts", "update_business_card",
        )

        /** An email address, a Korean phone number, or a fixture card id. */
        val CONTACT_VALUE = Regex(
            """[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""" +
                """|(?<!\d)0\d{1,2}-?\d{3,4}-?\d{4}(?!\d)""" +
                """|(?<![A-Za-z0-9])S\d{5}(?![A-Za-z0-9])""",
        )

        const val SYSTEM_INSTRUCTION = AppContainer.SYSTEM_INSTRUCTION
    }
}
