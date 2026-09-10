package com.example.hjp

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.example.hjp.search.AndroidEmbeddingGemmaEngine
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.DialogueAct
import com.hjp.agent.core.AgentKernel
import com.hjp.agent.core.AgentRuntimeEnvironment
import com.hjp.agent.core.AgentSessionManager
import com.hjp.agent.core.AgentTurnPolicy
import com.hjp.agent.core.CachingArtifactDigestProvider
import com.hjp.agent.core.ContactDirectory
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `RUN_D3` — the Ryeong multiturn compatibility run, on a real device, against the real models.
 *
 * Everything the agent uses here is the production class: the real kernel, the real deterministic
 * router, the real session and memory, the real contact directory over a real Room database, the
 * real Ryeong search service, the real EmbeddingGemma engine and the real LiteRT gateway loading the
 * real `.litertlm`.
 *
 * Two things are deliberately not real, and neither can change a routing or retrieval decision:
 *
 *  - the calendar and message backends are recording fakes, so no mail is sent and no event is
 *    saved on someone's actual device;
 *  - the card database is in-memory, so the evaluation fixture never lands in app storage.
 *
 * ## What changed for v3
 *
 * The v2-era version of this file was assembled differently from both the shipping app and the JVM
 * harness, and the difference silently disabled the thing being measured:
 *
 *  1. it read `ryeong/scenarios_v1.json` and asserted the 130-scenario inventory, while the JVM
 *     evaluation had moved to the 139-scenario v2 set;
 *  2. it built [AgentKernel] without `contactDirectory`, so the parameter defaulted to
 *     [ContactDirectory.None], every `TurnContext.directoryMatches` was empty, and the whole
 *     store-backed routing path was inert on device;
 *  3. its observation call used the three-argument `turnContext(...)` overload, so the act it
 *     recorded was computed against a router that knew of nobody.
 *
 * All three are fixed here, and the dataset now arrives through `syncRyeongEvalAssets`, which copies
 * it from the JVM authority under a digest check so the two can no longer drift apart.
 *
 * The observation is also cross-checked: the act this file derives and the act the kernel itself
 * recorded in [AgentKernel.diagnostics] must agree, and a disagreement is a validity failure rather
 * than a number nobody notices.
 *
 * Opt-in: without `-e ryeongDeviceRun true` this reports as skipped, so an ordinary
 * `connectedAndroidTest` never starts a 386-turn model run by accident.
 */
@RunWith(AndroidJUnit4::class)
class RyeongDeviceEvaluationTest {

    private val args = InstrumentationRegistry.getArguments()
    private val enabled = args.getString("ryeongDeviceRun") == "true"

    /** Optional cap for a smoke run. 0 means every scenario. */
    private val scenarioLimit = args.getString("ryeongScenarioLimit")?.toIntOrNull() ?: 0

    @Test
    fun ryeongCompatibilityOnDevice() {
        // Superseded, and refusing rather than merely unused.
        //
        // RUN_D3 was never executed, and it never will be: the evidence contract this class was
        // written against is the one v4 exists to replace. It shares a run identifier, an output
        // directory and a file set between its smoke and official modes, writes
        // `"actual_model_executed": true` as a literal, and accumulates its turn records in memory
        // until the end. Leaving it merely opt-in would leave a class on the device that, if invoked
        // by name, produces a file that looks official and is not.
        //
        // The file stays in the tree because it is what the v3 freeze pinned and what the v3 report
        // describes; deleting it would erase the thing the v4 defect list refers to. It simply
        // cannot run any more.
        error(
            "RyeongDeviceEvaluationTest is superseded by RyeongDeviceEvaluationV4Test " +
                "(official) and RyeongDeviceSmokeV4Test (smoke). RUN_D3 was never executed and " +
                "must not be; run RUN_D4 instead, following PHASE_B_COMMANDS.md.",
        )
        @Suppress("UNREACHABLE_CODE")
        if (!enabled) {
            android.util.Log.i(TAG, "skipped: pass -e ryeongDeviceRun true to run")
            return
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val startedAt = System.currentTimeMillis()

        // ---- device and model preflight, recorded before anything else ----------------------
        val isEmulator = Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true)
        val modelRoot = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val modelFile = File(modelRoot, "hjp-agent.litertlm")
        val deployment = ModelDeploymentResolver.resolve(
            modelFile,
            digestProvider = CachingArtifactDigestProvider(),
        )

        android.util.Log.i(TAG, "device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
            "abi=${Build.SUPPORTED_ABIS.joinToString("|")} emulator=$isEmulator")
        android.util.Log.i(TAG, "model exists=${modelFile.exists()} bytes=${modelFile.length()} " +
            "deployment=${deployment.diagnosticSummary()}")

        assertTrue("this run requires a physical device", !isEmulator)
        assertTrue("arm64-v8a required", Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        assertTrue("model missing at ${modelFile.absolutePath}", modelFile.exists())
        assertTrue("model not usable: ${deployment.diagnosticSummary()}", deployment.usable)

        // ---- frozen inputs, verified by digest before anything reads them ---------------------
        //
        // Hashed as bytes, not as decoded text: a digest taken after decoding would pass on a file
        // whose encoding changed, which is exactly the kind of drift this check exists to catch.
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val scenarioBytes = assets.open(SCENARIO_ASSET).use { it.readBytes() }
        val cardBytes = assets.open(CARD_ASSET).use { it.readBytes() }
        val scenarioSha = sha256(scenarioBytes)
        val cardSha = sha256(cardBytes)
        assertEquals("frozen scenario digest", FROZEN_SCENARIO_SHA256, scenarioSha)
        assertEquals("frozen card digest", FROZEN_CARDS_SHA256, cardSha)

        val scenarios = parseScenarios(String(scenarioBytes, Charsets.UTF_8))
        val cards = parseCards(String(cardBytes, Charsets.UTF_8))
        assertEquals("frozen scenario count", EXPECTED_SCENARIOS, scenarios.size)
        assertEquals("frozen turn count", EXPECTED_TURNS, scenarios.sumOf { it.turns.size })
        assertEquals("frozen kind count", EXPECTED_KINDS, scenarios.map { it.kind }.toSet().size)
        assertEquals("frozen card count", EXPECTED_CARDS, cards.size)
        assertEquals(
            "duplicate scenario index",
            scenarios.size,
            scenarios.map { it.index }.toSet().size,
        )
        assertEquals(
            "duplicate card id",
            cards.size,
            cards.map { it.id }.toSet().size,
        )
        scenarios.forEach { scenario ->
            assertEquals(
                "scenario ${scenario.index} turn depths are not 1..n",
                (1..scenario.turns.size).toList(),
                scenario.turns.map { it.depth },
            )
        }

        // ---- the production stack, assembled the way AppContainer assembles it -----------------
        val database = Room.inMemoryDatabaseBuilder(context, HjpDatabase::class.java).build()
        val repository = RoomBusinessCardRepository(context, database.businessCardDao(), Json)
        runBlocking { database.businessCardDao().insertAllAndReindex(cards.map { it.toEntity() }) }

        // One directory instance, used in all three places AppContainer uses it: the kernel, the
        // observation context, and the invalidation hook behind a card edit. Building a second one
        // for observation would be a second index with its own cache, which is the class of
        // divergence this run exists to rule out.
        val contactDirectory = RepositoryContactDirectory(repository)

        val embeddingEngine = AndroidEmbeddingGemmaEngine(context)
        var semanticInvocations = 0
        var semanticCandidateProducing = 0
        var keywordFallbacks = 0
        val realBackend = RyeongContactSearchBackend(
            repository = repository,
            embeddingEngineFactory = { OnDeviceEmbeddingEngine.production(embeddingEngine) },
            diagnostics = { event ->
                if (event.mode != "KEYWORD_ONLY") semanticInvocations++
                if (event.semanticResultCount > 0) semanticCandidateProducing++
                if (event.fallbackUsed) keywordFallbacks++
                android.util.Log.i(TAG, "search mode=${event.mode} fallback=${event.fallbackUsed} " +
                    "reason=${event.fallbackReason} kw=${event.keywordResultCount} " +
                    "sem=${event.semanticResultCount} elapsed=${event.elapsedMillis}")
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
                onUpdated = {
                    realBackend.invalidate()
                    // An edit can rename a card, and the name index is what decides whether a
                    // sentence is about a person. AppContainer invalidates both; so does this.
                    contactDirectory.invalidate()
                },
            ),
            CreateCalendarEventPlugin(calendar),
            OpenComposePlugin(messages),
            GetCurrentDateTimePlugin(),
        )
        val registry = DefaultToolRegistry(plugins.map { ToolImplementationCandidate(it) })
        val store = InMemoryAgentSessionStore()
        val gateway = LiteRtAgentModelGateway(
            modelFile,
            File(context.cacheDir, "litertlm-ryeong-eval"),
            LiteRtBackendPreference.CPU_ONLY,
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
            // Same guard production uses: if the system prompt plus the tool catalog cannot fit the
            // artifact's budget, fail with a diagnostic instead of letting the native layer reject
            // an over-long prompt mid-turn.
            contextPreflight = { snapshot ->
                ContextPreflight.check(
                    deployment,
                    SYSTEM_INSTRUCTION,
                    ContextPreflight.toolCatalogText(snapshot.contractsByModelName.values),
                )
            },
            contactDirectory = contactDirectory,
        )

        // ---- assembly preflight: fail before the first turn, not after 386 ---------------------
        assertEquals("engine mode", com.hjp.agent.core.AgentKernelMode.REACT, kernel.mode)

        val catalog = runBlocking {
            registry.snapshot(
                com.hjp.tool.contract.CatalogContext(
                    store.getOrCreate().sessionId,
                    environment.localeTag,
                    environment.grantedPermissions(),
                    environment.deviceCapabilities(),
                ),
            )
        }
        assertEquals(
            "tool catalog must be the production six",
            PRODUCTION_TOOLS,
            catalog.contractsByModelName.keys.toSortedSet(),
        )
        when (val preflight = kernel.let { contextPreflightOf(deployment, catalog) }) {
            is ContextPreflightResult.Ok -> Unit
            else -> throw AssertionError("context preflight failed: $preflight")
        }

        // The directory has to actually resolve somebody out of *this* fixture. The contact is
        // picked from the loaded cards at runtime — never a name written into this file — so the
        // canary cannot pass by coincidence on a dataset it was tuned against.
        val canaryCard = cards.firstOrNull { card ->
            card.name.length >= 2 && cards.count { it.name == card.name } == 1
        }
        assertNotNull("fixture has no uniquely-named contact to canary against", canaryCard)
        val canaryQuestion = "${canaryCard!!.name}씨 회사가 어디야?"
        val canaryMatches = runBlocking {
            contactDirectory.resolve(ContactNameCandidates.candidates(canaryQuestion))
        }
        assertTrue(
            "contact directory resolved nothing for a real fixture contact — " +
                "the store-backed routing path is not wired",
            canaryMatches.any { it.name == canaryCard.name && it.identifiesAPerson },
        )
        android.util.Log.i(TAG, "directory canary resolved ${canaryMatches.size} match(es)")

        val modelLoadStart = System.currentTimeMillis()
        val rows = StringBuilder()
        var turnsRun = 0
        var generatedTurns = 0
        var actionToolsOnSearchTurns = 0
        var leakage = 0
        var observationDisagreements = 0
        var firstTurnLatency = 0L
        val latencies = mutableListOf<Long>()
        val retrievalModes = linkedSetOf<String>()

        val selected = if (scenarioLimit > 0) scenarios.take(scenarioLimit) else scenarios
        try {
            runBlocking {
                selected.forEachIndexed { position, scenario ->
                    // Scenario isolation: a fresh generation before every scenario.
                    kernel.resetSession()
                    val opening = store.getOrCreate()
                    if (opening.conversationMemory.selectedContact != null ||
                        opening.transcript.isNotEmpty()
                    ) {
                        leakage++
                    }

                    scenario.turns.forEach { turn ->
                        val question = turn.question.trim()
                        val session = store.getOrCreate()

                        // The production router's own decision, on the state the kernel will see —
                        // including the store lookup. Identical inputs to the JVM harness: same
                        // session, same catalog keys, no grounded card, same directory, same
                        // normalisation, same candidate generator.
                        val matches = contactDirectory.resolve(
                            ContactNameCandidates.candidates(question),
                        )
                        val observedAct = DeterministicTurnRouter.act(
                            session.turnContext(
                                question,
                                catalog.contractsByModelName.keys,
                                null,
                                matches,
                            ),
                        )

                        tracer.clear()
                        val began = System.currentTimeMillis()
                        val events = kernel.runTurn(turn.question).toList()
                        val elapsed = System.currentTimeMillis() - began
                        if (turnsRun == 0) firstTurnLatency = elapsed
                        latencies += elapsed
                        turnsRun++
                        retrievalModes += tracer.modes

                        // What the kernel itself classified this turn as. Two derivations of one
                        // decision that disagree mean the observation is not describing the run.
                        val kernelAct = kernel.diagnostics.last?.dialogueAct
                        if (kernelAct != null && kernelAct != observedAct.name) {
                            observationDisagreements++
                            android.util.Log.e(
                                TAG,
                                "[${scenario.index}/${turn.depth}] observation disagreement: " +
                                    "observed=${observedAct.name} kernel=$kernelAct",
                            )
                        }

                        val answer = events.filterIsInstance<AgentEvent.FinalMessage>()
                            .lastOrNull()?.text
                            ?: events.filterIsInstance<AgentEvent.UserError>()
                                .lastOrNull()?.messageKo.orEmpty()
                        if (answer.isNotBlank()) generatedTurns++

                        val after = store.getOrCreate()
                        val action = after.conversationMemory.actions.lastOrNull()
                        val tools = action?.executedTools.orEmpty()
                        // Upstream's `no_cards` contract forbids *showing a card*, not running a
                        // tool this agent genuinely has. An action tool is only counted against the
                        // run when the turn was supposed to consult the store and did not.
                        val expectsStore = turn.expectedRoute == "search" || turn.expectedRoute == "followup"
                        val actionTools = tools.filter { it in ACTION_TOOLS }
                        val readTools = tools.filter { it in CONTACT_READ_TOOLS }
                        if (expectsStore && actionTools.isNotEmpty() && readTools.isEmpty()) {
                            actionToolsOnSearchTurns++
                        }

                        rows.append(
                            row(
                                scenario.index, turn.depth, scenario.kind, turn.expectedRoute,
                                observedAct.name, kernelAct, turn.goldCardIds, tracer.lastRanking,
                                after.conversationMemory.selectedContact?.cardId,
                                tools, actionTools, matches.map { it.name },
                                action?.outcomeType?.name, elapsed, answer,
                            ),
                        )
                        android.util.Log.i(
                            TAG,
                            "[${scenario.index}/${turn.depth}] act=$observedAct tools=$tools " +
                                "rank=${tracer.lastRanking.take(5)} ${elapsed}ms",
                        )
                    }
                    if ((position + 1) % 10 == 0) {
                        android.util.Log.i(TAG, "progress ${position + 1}/${selected.size} scenarios")
                    }
                }
            }
        } finally {
            val outDir = File(context.getExternalFilesDir(null), "ryeong_device_eval_v3").apply { mkdirs() }
            val elapsedTotal = System.currentTimeMillis() - startedAt
            val sorted = latencies.sorted()
            fun pct(p: Double) = if (sorted.isEmpty()) 0L else
                sorted[minOf(sorted.size - 1, maxOf(0, Math.round(p * (sorted.size - 1)).toInt()))]

            val header = buildString {
                append("{\n")
                append("""  "run_id": ${q(RUN_ID)},""").append('\n')
                append("""  "axis": "search / focus / follow-up compatibility only - NOT a whole-agent tool score",""").append('\n')
                append("""  "dataset": {"asset": ${q(SCENARIO_ASSET)}, "sha256": ${q(scenarioSha)}, """)
                append(""""scenarios": ${scenarios.size}, "turns": ${scenarios.sumOf { it.turns.size }}, """)
                append(""""kinds": ${scenarios.map { it.kind }.toSet().size}},""").append('\n')
                append("""  "cards": {"asset": ${q(CARD_ASSET)}, "sha256": ${q(cardSha)}, "count": ${cards.size}},""").append('\n')
                append("""  "assembly": {"engine_mode": ${q(kernel.mode.name)}, """)
                append(""""contact_directory": "RepositoryContactDirectory", """)
                append(""""tool_catalog": ${jsonArr(catalog.contractsByModelName.keys.sorted())}},""").append('\n')
                append("""  "device": {"manufacturer": ${q(Build.MANUFACTURER)}, "model": ${q(Build.MODEL)}, """)
                append(""""sdk": ${Build.VERSION.SDK_INT}, "abis": ${jsonArr(Build.SUPPORTED_ABIS.toList())}, """)
                append(""""fingerprint": ${q(Build.FINGERPRINT)}, "emulator": false},""").append('\n')
                append("""  "model": {"path": ${q(modelFile.absolutePath)}, "bytes": ${modelFile.length()}, """)
                append(""""deployment": ${jsonMap(deployment.diagnosticSummary())}},""").append('\n')
                append("""  "actual_model_executed": true,""").append('\n')
                append("""  "semantic": {"invocations": $semanticInvocations, """)
                append(""""candidate_producing_invocations": $semanticCandidateProducing, """)
                append(""""keyword_fallbacks": $keywordFallbacks, """)
                append(""""modes": ${jsonArr(retrievalModes.toList())}},""").append('\n')
                append("""  "scenarios_run": ${selected.size}, "turns_run": $turnsRun,""").append('\n')
                append("""  "generated_turns": $generatedTurns,""").append('\n')
                append("""  "action_tools_on_search_turns": $actionToolsOnSearchTurns,""").append('\n')
                append("""  "observation_disagreements": $observationDisagreements,""").append('\n')
                append("""  "cross_scenario_leakage": $leakage,""").append('\n')
                append("""  "elapsed_millis": $elapsedTotal, "model_load_to_first_turn_millis": ${modelLoadStart.let { firstTurnLatency }},""").append('\n')
                append("""  "latency": {"p50": ${pct(0.5)}, "p95": ${pct(0.95)}, "max": ${sorted.lastOrNull() ?: 0}},""").append('\n')
                append("""  "turn_records": [""").append('\n')
            }
            val body = rows.toString().trimEnd(',', '\n')
            File(outDir, "device_result.json").writeText("$header$body\n  ]\n}\n")
            File(outDir, "device_run_status.json").writeText(
                """{"run_id": ${q(RUN_ID)}, "completed_turns": $turnsRun, """ +
                    """"expected_turns": ${selected.sumOf { it.turns.size }}, """ +
                    """"scenarios": ${selected.size}, "actual_model_executed": true, """ +
                    """"semantic_invocations": $semanticInvocations, """ +
                    """"observation_disagreements": $observationDisagreements, """ +
                    """"cross_scenario_leakage": $leakage, "elapsed_millis": $elapsedTotal}""" + "\n",
            )
            android.util.Log.i(TAG, "wrote ${outDir.absolutePath} turns=$turnsRun")
            gateway.close()
            database.close()
        }

        // Validity, asserted after the record is on disk so a failure still leaves evidence.
        assertEquals("cross-scenario leakage", 0, leakage)
        assertEquals(
            "the observation and the kernel disagreed on the dialogue act",
            0,
            observationDisagreements,
        )
    }

    private fun contextPreflightOf(
        deployment: com.hjp.agent.core.ModelDeployment,
        catalog: com.hjp.tool.contract.ToolCatalogSnapshot,
    ): ContextPreflightResult = ContextPreflight.check(
        deployment,
        SYSTEM_INSTRUCTION,
        ContextPreflight.toolCatalogText(catalog.contractsByModelName.values),
    )

    // ---- read-only observation ------------------------------------------------------------------

    /** Forwards every call untouched; remembers only what came back. Never re-ranks or filters. */
    private class TracingBackend(private val delegate: ContactSearchBackend) : ContactSearchBackend {
        var lastRanking: List<String> = emptyList()
            private set
        val modes = linkedSetOf<String>()

        override suspend fun search(query: String, limit: Int): ContactSearchResponse {
            val response = delegate.search(query, limit)
            lastRanking = response.hits.map { it.card.id }
            modes += response.mode
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

    /** The capability set AppContainer advertises, so the catalog here is the catalog that ships. */
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

    // ---- frozen input parsing (kept self-contained so the sealed JVM sources stay untouched) ------

    private data class DeviceTurn(
        val depth: Int,
        val question: String,
        val expectedRoute: String?,
        val goldCardIds: List<String>,
    )

    private data class DeviceScenario(val index: Int, val kind: String, val turns: List<DeviceTurn>)

    private fun parseScenarios(raw: String): List<DeviceScenario> {
        val root = Json.parseToJsonElement(raw) as JsonObject
        val list = root["scenarios"] as JsonArray
        return list.map { element ->
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

    private fun parseCards(raw: String): List<BusinessCardRecord> {
        val list = Json.parseToJsonElement(raw) as JsonArray
        return list.map { element ->
            val card = element as JsonObject
            fun s(key: String) = (card[key] as? JsonPrimitive)?.content.orEmpty()
            BusinessCardRecord(
                id = s("id"), name = s("name"), nameEn = s("nameEn"), company = s("company"),
                title = s("title"), department = s("department"), industry = s("industry"),
                location = s("location"), phone = s("phone"), email = s("email"),
                address = s("address"), memo = s("memo"),
            )
        }
    }

    private fun BusinessCardRecord.toEntity() = BusinessCardEntity(
        id = id, name = name, nameEn = nameEn, company = company, title = title,
        department = department, industry = industry, location = location, phone = phone,
        mobile = "", email = email, address = address, website = "", memo = memo,
        tagsJson = "[]", updatedAt = "",
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- JSON emission ---------------------------------------------------------------------------

    private fun row(
        scenario: Int, depth: Int, kind: String, expectedRoute: String?, act: String,
        kernelAct: String?, gold: List<String>, ranking: List<String>, focus: String?,
        tools: List<String>, actionTools: List<String>, directoryNames: List<String>,
        outcome: String?, elapsed: Long, answer: String,
    ): String = buildString {
        append("    {")
        append(""""scenario": $scenario, "depth": $depth, "kind": ${q(kind)}, """)
        append(""""expected_route": ${expectedRoute?.let { q(it) } ?: "null"}, """)
        append(""""observed_act": ${q(act)}, """)
        append(""""kernel_dialogue_act": ${kernelAct?.let { q(it) } ?: "null"}, """)
        append(""""gold": ${jsonArr(gold)}, "ranking_top5": ${jsonArr(ranking.take(5))}, """)
        append(""""ranking_size": ${ranking.size}, """)
        append(""""selected_card_id": ${focus?.let { q(it) } ?: "null"}, """)
        append(""""directory_matches": ${jsonArr(directoryNames)}, """)
        append(""""tools": ${jsonArr(tools)}, "action_tools": ${jsonArr(actionTools)}, """)
        append(""""outcome": ${outcome?.let { q(it) } ?: "null"}, """)
        append(""""latency_millis": $elapsed, """)
        append(""""answer": ${q(answer.take(400))}""")
        append("},\n")
    }

    private fun q(value: String): String = "\"" + value
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun jsonArr(values: List<String>) = values.joinToString(",", "[", "]") { q(it) }

    private fun jsonMap(values: Map<String, String>) =
        values.entries.joinToString(",", "{", "}") { "${q(it.key)}:${q(it.value)}" }

    companion object {
        const val TAG = "RyeongDeviceEval"
        const val RUN_ID = "RYEONG_PRODUCTION_COMPATIBILITY_V3_RUN_D3_DEVICE_ACTUAL_MODEL_BASELINE"

        /** The dataset assets, produced by the `syncRyeongEvalAssets` Gradle task. */
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

        val ACTION_TOOLS = setOf("open_compose", "create_calendar_event", "update_business_card")
        val CONTACT_READ_TOOLS = setOf("search_contacts", "get_contact")

        /** The catalog the shipping app exposes. A missing or extra tool is a different agent. */
        val PRODUCTION_TOOLS = sortedSetOf(
            "create_calendar_event", "get_contact", "get_current_datetime",
            "open_compose", "search_contacts", "update_business_card",
        )

        /** The prompt the app ships, so the device run measures the agent that ships. */
        const val SYSTEM_INSTRUCTION = AppContainer.SYSTEM_INSTRUCTION
    }
}
