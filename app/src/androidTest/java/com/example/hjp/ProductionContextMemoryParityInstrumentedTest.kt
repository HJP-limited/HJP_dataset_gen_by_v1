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
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.contract.AgentModelSession
import com.hjp.agent.contract.AgentRuntimeCounters
import com.hjp.agent.contract.CountingAgentModelGateway
import com.hjp.agent.contract.FinalAnswerInput
import com.hjp.agent.contract.ModelBoundaryKind
import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.agent.contract.AgentEvent
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
import com.hjp.searchlookup.BusinessCard
import com.hjp.searchlookup.EmbeddingEngine
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.android.CalendarComposerBackend
import com.hjp.tool.android.CalendarDraft
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.MessageChannel
import com.hjp.tool.android.MessageComposerBackend
import com.hjp.tool.android.MessageDraft
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
import kotlinx.serialization.encodeToString
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A-6 physical-device observation of the production context/memory pipeline.
 *
 * The host must back up and restore the persisted production Room database around this test. The
 * frozen 1,000-card fixture is injected once, and the production EmbeddingGemma index is warmed once
 * before any agent turn. All turns share the same Room database, vector store, agent session, native
 * LiteRT conversation and model engine.
 *
 * [ObservingGateway] and [RecordingExecutor] are transparent decorators. They forward the same
 * immutable inputs to the production implementation and return its own result unchanged. The only
 * substituted components are the final Android Intent surfaces for mail/calendar; A-6 validates the
 * selected tool and arguments, while opening external UI is explicitly A-7 scope.
 */
@RunWith(AndroidJUnit4::class)
class ProductionContextMemoryParityInstrumentedTest {
    @Test
    fun actualGemmaReceivesProductionMemoryAndKeepsCorrectedTargetAcrossTurns() = runBlocking {
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        assumeFalse(AppContainer.isAndroidEmulator())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val reusePersisted = InstrumentationRegistry.getArguments()
            .getString("a6ReusePersisted") == "true"
        val datasetBytes = instrumentation.context.assets.open(CARD_ASSET).use { it.readBytes() }
        assertEquals(FROZEN_CARDS_SHA256, sha256(datasetBytes))
        val cards = parseCards(String(datasetBytes, Charsets.UTF_8))
        assertEquals(EXPECTED_CARDS, cards.size)
        assertEquals(EXPECTED_CARDS, cards.map { it.id }.distinct().size)

        val modelRoot = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val modelFile = File(modelRoot, "hjp-agent.litertlm")
        val deployment = ModelDeploymentResolver.resolve(
            modelFile,
            digestProvider = CachingArtifactDigestProvider(),
            verificationPolicy = ArtifactVerificationPolicy.REQUIRE_OFFICIAL_GENERATIVE_ARTIFACT,
        )
        assertTrue("official Gemma artifact is not usable: ${deployment.diagnosticSummary()}", deployment.usable)
        assertEquals(
            ModelDeploymentResolver.OFFICIAL_GENERATIVE_ARTIFACT_SHA256,
            deployment.verifiedSha256,
        )
        assertEquals(3_072, deployment.budget.maxPromptTokens)

        val database = HjpDatabase.getInstance(context)
        val dao = database.businessCardDao()
        if (!reusePersisted) {
            clearProductionTables(database)
            dao.insertAllAndReindex(cards)
        } else {
            // A failed focused attempt may already have exercised update_business_card. Restore
            // the frozen card rows and FTS from the same verified asset while deliberately leaving
            // card_embeddings intact. Startup then re-embeds only genuinely stale fingerprints,
            // never the full 1,000-card corpus.
            dao.insertAllAndReindex(cards)
        }
        assertEquals(EXPECTED_CARDS, dao.count())
        assertEquals(EXPECTED_CARDS, tableCount(database, "business_cards_fts"))

        val repository = RoomBusinessCardRepository(context, dao, Json)
        if (reusePersisted) {
            assertEquals(cards.map { it.id }.toSet(), repository.loadAll().map { it.id }.toSet())
        }
        val nativeEmbedding = AndroidEmbeddingGemmaEngine(context)
        assertTrue(nativeEmbedding.diagnosticStatus(), nativeEmbedding.isModelBacked)
        val countedEmbedding = CountingEmbeddingEngine(OnDeviceEmbeddingEngine.production(nativeEmbedding))
        val searchDiagnostics = mutableListOf<SearchDiagnostics>()
        val backend = RyeongContactSearchBackend(
            repository = repository,
            embeddingEngineFactory = { countedEmbedding },
            diagnostics = { event ->
                searchDiagnostics += event
                Log.i(
                    TAG,
                    "RETRIEVAL mode=${event.mode} fallback=${event.fallbackUsed} " +
                        "keyword=${event.keywordResultCount} semantic=${event.semanticResultCount} " +
                        "ranked=${event.rankedCardIds.take(10)} init_ms=${event.initializationMillis} " +
                        "query_ms=${event.queryEmbeddingMillis} elapsed_ms=${event.elapsedMillis}",
                )
            },
        )

        // One and only full indexing pass. Every A-6 turn below reuses these persisted vectors.
        val warmStarted = SystemClock.elapsedRealtime()
        val warm = backend.search(INITIAL_QUERY, 10)
        assertEquals("HYBRID", warm.mode)
        assertFalse(warm.fallbackUsed)
        if (reusePersisted) {
            assertTrue(
                "persisted vectors were not reused: document_calls=${countedEmbedding.documentCalls}",
                countedEmbedding.documentCalls < EXPECTED_CARDS,
            )
        } else {
            assertEquals(EXPECTED_CARDS, countedEmbedding.documentCalls)
        }
        assertEquals(EXPECTED_CARDS, repository.loadEmbeddings(countedEmbedding.name()).size)
        val fullIndexDocumentCalls = countedEmbedding.documentCalls
        Log.i(
            TAG,
            "A6_DB_READY cards=${dao.count()} fts=${tableCount(database, "business_cards_fts")} " +
                "vectors=${repository.loadEmbeddings(countedEmbedding.name()).size} " +
                "document_calls=$fullIndexDocumentCalls warm_ms=${SystemClock.elapsedRealtime() - warmStarted}",
        )

        val contactDirectory = RepositoryContactDirectory(repository)
        val calendar = RecordingCalendarBackend()
        val messages = RecordingMessageBackend()
        val plugins = listOf(
            SearchContactsPlugin(backend),
            GetContactPlugin(backend),
            UpdateBusinessCardPlugin(
                repository,
                onUpdated = {
                    backend.invalidate()
                    contactDirectory.invalidate()
                },
            ),
            CreateCalendarEventPlugin(calendar),
            OpenComposePlugin(messages),
            GetCurrentDateTimePlugin(),
        )
        val registry = DefaultToolRegistry(plugins.map { ToolImplementationCandidate(it) })
        val toolObserver = RecordingExecutor(DefaultToolExecutor(registry))
        val counters = AgentRuntimeCounters()
        val actualGateway = LiteRtAgentModelGateway(
            modelFile,
            File(context.cacheDir, "litertlm-a6-context-memory"),
            LiteRtBackendPreference.CPU_ONLY,
            counters,
        )
        // Same production counter decorator as AppContainer; the A-6 observer remains outside it so
        // both the runtime counter and the exact immutable value forwarded to LiteRT are visible.
        val modelObserver = ObservingGateway(CountingAgentModelGateway(actualGateway, counters))
        val store = InMemoryAgentSessionStore()
        val sessionManager = AgentSessionManager(
            store,
            modelObserver,
            AppContainer.SYSTEM_INSTRUCTION,
            "ko-KR",
        )
        val environment = A6Environment()
        val kernel = AgentKernel(
            registry = registry,
            toolExecutor = toolObserver,
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
                    deployment,
                    AppContainer.SYSTEM_INSTRUCTION,
                    ContextPreflight.toolCatalogText(snapshot.contractsByModelName.values),
                )
            },
            contactDirectory = contactDirectory,
            runtimeCounters = counters,
        )

        val evidenceFile = File(
            requireNotNull(context.getExternalFilesDir("a6")),
            "production_context_memory_parity.json",
        )
        evidenceFile.parentFile?.mkdirs()
        val turns = mutableListOf<TurnEvidence>()
        var firstId: String? = null
        var secondId: String? = null
        var firstEmail: String? = null
        var secondEmail: String? = null
        var finalChecks: JsonObject? = null

        suspend fun persist(stage: String, finished: Boolean = false) {
            val memory = store.getOrCreate()
            val memoryInfo = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val roomCards = dao.count()
            val roomFtsRows = tableCount(database, "business_cards_fts")
            val storedVectors = repository.loadEmbeddings(countedEmbedding.name()).size
            val report = buildJsonObject {
                put("schema", "hjp.device_parity.a6.v1")
                put("stage", stage)
                put("finished", finished)
                put("device_model", Build.MODEL)
                put("device_abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                put("pid", Process.myPid())
                put("dataset_kind", "evaluation-injected frozen dataset; not shipping default asset")
                put("database_prep_mode", if (reusePersisted) "REUSE_PERSISTED" else "INJECT_ONCE")
                put("frozen_rows_restored_before_reuse", reusePersisted)
                put("dataset_sha256", sha256(datasetBytes))
                put("room_cards", roomCards)
                put("room_fts_rows", roomFtsRows)
                put("stored_vectors", storedVectors)
                put("embedding_document_calls", countedEmbedding.documentCalls)
                put("embedding_query_calls", countedEmbedding.queryCalls)
                put("embedding_engine", countedEmbedding.name())
                put("embedding_model_backed", countedEmbedding.isModelBacked())
                put("generative_artifact", deployment.artifactId)
                put("generative_sha256", deployment.verifiedSha256.orEmpty())
                put("context_budget", deployment.budget.maxPromptTokens)
                put("history_strategy", ConversationHistoryStrategy.APP_CANONICAL_BOOTSTRAP.name)
                put("model_boundary", modelObserver.boundaryKind.name)
                put("system_instruction_sha256", sha256(AppContainer.SYSTEM_INSTRUCTION.toByteArray()))
                put("system_instruction", AppContainer.SYSTEM_INSTRUCTION)
                put("first_card_id", firstId ?: "")
                put("second_card_id", secondId ?: "")
                put("turns", buildJsonArray { turns.forEach { add(it.toJson()) } })
                put("model_trace", buildJsonArray { modelObserver.traces.forEach { add(it.toJson()) } })
                put("tool_trace", buildJsonArray { toolObserver.traces.forEach { add(it.toJson()) } })
                put("retrieval_modes", buildJsonArray { searchDiagnostics.forEach { add(it.mode) } })
                put("message_drafts", buildJsonArray { messages.drafts.forEach { add(it.toJson()) } })
                put("calendar_drafts", buildJsonArray { calendar.drafts.forEach { add(it.toJson()) } })
                put("final_memory", memory.toJson())
                finalChecks?.let { put("hard_checks", it) }
                put("runtime_counters", buildJsonObject {
                    counters.snapshot().asLongMap().forEach { (key, value) -> put(key, value) }
                })
                put("total_pss_kb", memoryInfo.totalPss)
                put("native_pss_kb", memoryInfo.nativePss)
            }
            evidenceFile.writeText(PRETTY_JSON.encodeToString(report))
        }

        suspend fun turn(label: String, text: String): TurnEvidence {
            modelObserver.currentTurn = label
            toolObserver.currentTurn = label
            val before = store.getOrCreate().snapshotJson()
            val modelStart = modelObserver.traces.size
            val toolStart = toolObserver.traces.size
            val started = SystemClock.elapsedRealtime()
            val events = kernel.runTurn(text).toList()
            val after = store.getOrCreate().snapshotJson()
            val finalText = events.filterIsInstance<AgentEvent.FinalMessage>().lastOrNull()?.text
                ?: events.filterIsInstance<AgentEvent.UserError>().lastOrNull()?.messageKo.orEmpty()
            val actualTurnId = events.filterIsInstance<AgentEvent.TurnStarted>().single().turnId
            val typedOutcome = store.getOrCreate().conversationMemory.actions
                .lastOrNull { it.turnId == actualTurnId }
                ?.status?.name
                ?: kernel.diagnostics.last?.outcome.orEmpty()
            val evidence = TurnEvidence(
                label = label,
                userText = text,
                elapsedMillis = SystemClock.elapsedRealtime() - started,
                finalText = finalText,
                beforeMemory = before,
                afterMemory = after,
                modelTraceFrom = modelStart,
                modelTraceTo = modelObserver.traces.size,
                toolTraceFrom = toolStart,
                toolTraceTo = toolObserver.traces.size,
                route = kernel.diagnostics.last?.routePlan.orEmpty(),
                typedOutcome = typedOutcome,
                promptSections = kernel.diagnostics.last?.promptSections.orEmpty(),
                promptTokens = kernel.diagnostics.last?.promptTokensEstimated ?: 0,
            )
            turns += evidence
            Log.i(
                TAG,
                "TURN label=$label route=${evidence.route} selected=" +
                    "${store.getOrCreate().conversationMemory.selectedContact?.cardId} " +
                    "tools=${toolObserver.traces.subList(toolStart, toolObserver.traces.size).map { it.tool }} " +
                    "model_steps=${modelObserver.traces.size - modelStart} elapsed_ms=${evidence.elapsedMillis}",
            )
            persist(label)
            return evidence
        }

        try {
            persist("db_and_vectors_ready")

            turn("search", "블루오션컨설팅 AI 개발자 명함을 두 명만 찾아줘.")
            val searchMemory = store.getOrCreate().conversationMemory
            firstId = searchMemory.candidateContacts.getOrNull(0)?.cardId
            secondId = searchMemory.candidateContacts.getOrNull(1)?.cardId
            firstEmail = firstId?.let { repository.getById(it)?.email }
            secondEmail = secondId?.let { repository.getById(it)?.email }
            persist("search_candidates_captured")

            val case1 = turn(
                "ordinal_second_compose",
                "두 번째 사람에게 제목은 A-6 순서 확인, 내용은 두 번째 후보 확인입니다로 이메일 작성해줘.",
            )
            val case2 = turn(
                "pronoun_calendar",
                "그 사람과 2027년 9월 15일 오후 3시에 A-6 맥락 검증 회의 일정을 만들어줘.",
            )
            val case3 = turn(
                "correction_first",
                "아니, 첫 번째 사람에게 제목은 A-6 정정, 내용은 첫 번째 후보로 정정합니다로 이메일 작성해줘.",
            )
            val case4 = turn(
                "corrected_target_update",
                "그 사람 명함의 메모를 A-6 correction target verified로 수정해줘.",
            )
            turn("filler_one", "고마워. 지금까지 진행은 잘 이해했어.")
            turn("filler_two", "현재 날짜와 시간을 알려줘.")
            val case5 = turn(
                "late_reference_compose",
                "아까 그분에게 제목은 A-6 장기 참조, 내용은 여러 턴 뒤 대상 유지 확인입니다로 이메일 작성해줘.",
            )

            val finalMemory = store.getOrCreate().conversationMemory
            val expectedFirst = firstId
            val expectedSecond = secondId
            val fullReindexCount = countedEmbedding.documentCalls / EXPECTED_CARDS
            fun modelSteps(turn: TurnEvidence) = modelObserver.traces.subList(
                turn.modelTraceFrom,
                turn.modelTraceTo,
            )
            fun toolSteps(turn: TurnEvidence) = toolObserver.traces.subList(
                turn.toolTraceFrom,
                turn.toolTraceTo,
            )
            fun currentTargetSeen(turn: TurnEvidence, cardId: String?) = cardId != null &&
                modelSteps(turn).any { trace ->
                    trace.kind == "user" && trace.renderedPrompt.contains("current_target: card_id=$cardId")
                }
            fun freshThenTerminal(
                turn: TurnEvidence,
                cardId: String?,
                purpose: String,
                terminal: String,
            ): Boolean {
                if (cardId == null) return false
                val steps = toolSteps(turn)
                val getIndex = steps.indexOfFirst { trace ->
                    trace.tool == "get_contact" && trace.arguments.string("card_id") == cardId &&
                        trace.arguments.string("purpose") == purpose && trace.success
                }
                val terminalIndex = steps.indexOfFirst { it.tool == terminal && it.success }
                return getIndex >= 0 && terminalIndex > getIndex
            }
            fun composeUsesVerifiedEmail(turn: TurnEvidence, cardId: String?, email: String?): Boolean {
                if (cardId == null || email.isNullOrBlank()) return false
                val steps = toolSteps(turn)
                val get = steps.firstOrNull {
                    it.tool == "get_contact" && it.arguments.string("card_id") == cardId && it.success
                } ?: return false
                val verified = get.resultData?.string("email")
                val compose = steps.firstOrNull { it.tool == "open_compose" && it.success }
                    ?: return false
                return verified == email && compose.arguments.string("to") == email &&
                    compose.arguments.string("to") != cardId
            }
            fun updateTargets(turn: TurnEvidence, cardId: String?): Boolean = cardId != null &&
                toolSteps(turn).any {
                    it.tool == "update_business_card" && it.arguments.string("card_id") == cardId && it.success
                }
            fun hasFalseSuccess(turn: TurnEvidence): Boolean {
                val terminalSucceeded = toolSteps(turn).any {
                    it.success && it.tool in setOf(
                        "open_compose", "create_calendar_event", "update_business_card",
                    )
                }
                return !terminalSucceeded && FALSE_SUCCESS_REGEX.containsMatchIn(turn.finalText)
            }

            val focusedChecks = linkedMapOf(
                "case1_current_target_second" to currentTargetSeen(case1, expectedSecond),
                "case1_fresh_email_then_compose" to freshThenTerminal(
                    case1, expectedSecond, "email", "open_compose",
                ),
                "case1_verified_email_provenance" to composeUsesVerifiedEmail(
                    case1, expectedSecond, secondEmail,
                ),
                "case2_current_target_second" to currentTargetSeen(case2, expectedSecond),
                "case2_fresh_calendar_then_event" to freshThenTerminal(
                    case2, expectedSecond, "calendar", "create_calendar_event",
                ),
                "case3_current_target_first" to currentTargetSeen(case3, expectedFirst),
                "case3_fresh_email_then_compose" to freshThenTerminal(
                    case3, expectedFirst, "email", "open_compose",
                ),
                "case3_verified_email_provenance" to composeUsesVerifiedEmail(
                    case3, expectedFirst, firstEmail,
                ),
                "case4_current_target_first" to currentTargetSeen(case4, expectedFirst),
                "case4_fresh_display_then_update" to freshThenTerminal(
                    case4, expectedFirst, "display", "update_business_card",
                ),
                "case4_correct_card_id" to updateTargets(case4, expectedFirst),
                "case5_current_target_first" to currentTargetSeen(case5, expectedFirst),
                "case5_fresh_email_then_compose" to freshThenTerminal(
                    case5, expectedFirst, "email", "open_compose",
                ),
                "case5_verified_email_provenance" to composeUsesVerifiedEmail(
                    case5, expectedFirst, firstEmail,
                ),
                "no_false_success" to listOf(case1, case2, case3, case4, case5).none(::hasFalseSuccess),
            )
            val focusedPass = focusedChecks.values.all { it }
            val hardChecks = buildJsonObject {
                put("search_has_two_candidates", expectedFirst != null && expectedSecond != null)
                put("candidate_ids_distinct", expectedFirst != null && expectedFirst != expectedSecond)
                put("official_actual_model_boundary", modelObserver.boundaryKind == ModelBoundaryKind.ACTUAL_MODEL)
                put("model_user_inferences", modelObserver.traces.count { it.kind == "user" })
                put("session_state_seen_by_gateway", modelObserver.traces.any {
                    it.kind == "user" && it.renderedPrompt.contains("[session_state]")
                })
                put("candidate_order_seen_by_gateway", expectedFirst != null && expectedSecond != null &&
                    modelObserver.traces.any {
                        it.renderedPrompt.contains("1.") && it.renderedPrompt.contains("card_id=$expectedFirst") &&
                            it.renderedPrompt.contains("2.") && it.renderedPrompt.contains("card_id=$expectedSecond")
                    })
                put("final_selected_is_corrected_first", expectedFirst != null && finalMemory.selectedContact?.cardId == expectedFirst)
                put("full_1000_embedding_passes", fullReindexCount)
                put("no_second_full_reindex", countedEmbedding.documentCalls < EXPECTED_CARDS * 2)
                put("first_email", firstEmail.orEmpty())
                put("second_email", secondEmail.orEmpty())
                put("focused_checks", buildJsonObject {
                    focusedChecks.forEach { (name, passed) -> put(name, passed) }
                })
                put("focused_pass", focusedPass)
            }
            finalChecks = hardChecks
            Log.i(TAG, "A6_HARD_CHECKS $hardChecks")
            persist("completed", finished = true)

            // Infrastructure assertions only. Model/reference correctness is intentionally reported
            // from the trace rather than terminating the first failing scenario and hiding the rest.
            assertTrue("search did not establish at least two candidates", expectedFirst != null && expectedSecond != null)
            assertTrue("no actual Gemma user inference was observed", modelObserver.traces.any { it.kind == "user" })
            assertTrue("production session state never reached the actual gateway", modelObserver.traces.any {
                it.kind == "user" && it.renderedPrompt.contains("[session_state]")
            })
            assertTrue("a second full 1,000-card embedding pass occurred", countedEmbedding.documentCalls < 2_000)
            assertTrue("evidence file missing", evidenceFile.isFile && evidenceFile.length() > 0)
            assertTrue("focused A-6 chain failed: $focusedChecks", focusedPass)
        } finally {
            runCatching { persist("finally", finished = turns.size == 8) }
            // Keep any retry on the exact same frozen rows. Persisted vectors are not deleted.
            runCatching { dao.insertAllAndReindex(cards) }
            sessionManager.close()
            nativeEmbedding.close()
            database.close()
            Log.i(
                TAG,
                "A6_FINISH evidence=${evidenceFile.absolutePath} turns=${turns.size} " +
                    "document_calls=${countedEmbedding.documentCalls} query_calls=${countedEmbedding.queryCalls}",
            )
        }
    }

    private fun clearProductionTables(database: HjpDatabase) {
        val db = database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM card_embeddings")
            db.execSQL("DELETE FROM business_cards_fts")
            db.execSQL("DELETE FROM business_cards")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun tableCount(database: HjpDatabase, table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.content?.trim()

    private fun parseCards(raw: String): List<BusinessCardEntity> =
        (Json.parseToJsonElement(raw) as JsonArray).map { element ->
            val card = element as JsonObject
            fun text(key: String) = (card[key] as? JsonPrimitive)?.content.orEmpty()
            val tags = text("tags").split(',').map(String::trim).filter(String::isNotEmpty)
            BusinessCardEntity(
                id = text("id"), name = text("name"), nameEn = text("nameEn"),
                company = text("company"), title = text("title"), department = text("department"),
                industry = text("industry"), location = text("location"), phone = text("phone"),
                mobile = "", email = text("email"), address = text("address"), website = "",
                memo = text("memo"), tagsJson = Json.encodeToString(tags), updatedAt = "",
            )
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private data class TurnEvidence(
        val label: String,
        val userText: String,
        val elapsedMillis: Long,
        val finalText: String,
        val beforeMemory: JsonObject,
        val afterMemory: JsonObject,
        val modelTraceFrom: Int,
        val modelTraceTo: Int,
        val toolTraceFrom: Int,
        val toolTraceTo: Int,
        val route: String,
        val typedOutcome: String,
        val promptSections: List<String>,
        val promptTokens: Int,
    ) {
        fun toJson() = buildJsonObject {
            put("label", label); put("user", userText); put("elapsed_ms", elapsedMillis)
            put("final", finalText); put("route", route); put("typed_outcome", typedOutcome)
            put("prompt_tokens", promptTokens)
            put("prompt_sections", buildJsonArray { promptSections.forEach { add(it) } })
            put("model_trace_from", modelTraceFrom); put("model_trace_to", modelTraceTo)
            put("tool_trace_from", toolTraceFrom); put("tool_trace_to", toolTraceTo)
            put("before_memory", beforeMemory); put("after_memory", afterMemory)
        }
    }

    private data class ModelTrace(
        val turn: String,
        val kind: String,
        val renderedPrompt: String = "",
        val sectionNames: List<String> = emptyList(),
        val estimatedTokens: Int = 0,
        val inputPayload: String = "",
        val decision: ModelDecision,
    ) {
        fun toJson() = buildJsonObject {
            put("turn", turn); put("kind", kind); put("rendered_prompt", renderedPrompt)
            put("rendered_prompt_sha256", sha256Static(renderedPrompt.toByteArray()))
            put("sections", buildJsonArray { sectionNames.forEach { add(it) } })
            put("estimated_tokens", estimatedTokens); put("input_payload", inputPayload)
            put("decision", decisionToJson(decision))
        }
    }

    private class ObservingGateway(private val delegate: AgentModelGateway) : AgentModelGateway {
        var currentTurn: String = "setup"
        val traces = mutableListOf<ModelTrace>()
        override val boundaryKind: ModelBoundaryKind get() = delegate.boundaryKind

        override suspend fun openSession(config: ModelSessionConfig): AgentModelSession =
            ObservingSession(delegate.openSession(config), this)

        override fun close() = delegate.close()

        private class ObservingSession(
            private val delegate: AgentModelSession,
            private val owner: ObservingGateway,
        ) : AgentModelSession {
            override val catalogRevision: String get() = delegate.catalogRevision

            override suspend fun decide(input: ModelInput): ModelDecision {
                val user = input as ModelInput.User
                val decision = delegate.decide(input)
                owner.traces += ModelTrace(
                    turn = owner.currentTurn,
                    kind = "user",
                    renderedPrompt = user.promptContext.render(user.text),
                    sectionNames = user.promptContext.sections.map { it.name },
                    estimatedTokens = user.promptContext.estimatedTokens,
                    decision = decision,
                )
                return decision
            }

            override suspend fun continueWithToolResult(result: ModelToolResponse): ModelDecision {
                val decision = delegate.continueWithToolResult(result)
                owner.traces += ModelTrace(
                    turn = owner.currentTurn,
                    kind = "tool_result",
                    inputPayload = result.payload.toString(),
                    decision = decision,
                )
                return decision
            }

            override suspend fun continueWithWorkflowNote(note: com.hjp.agent.contract.ModelWorkflowNote): ModelDecision {
                val decision = delegate.continueWithWorkflowNote(note)
                owner.traces += ModelTrace(
                    turn = owner.currentTurn,
                    kind = "workflow_note",
                    inputPayload = note.text,
                    decision = decision,
                )
                return decision
            }

            override fun streamFinal(input: FinalAnswerInput): Flow<String> = delegate.streamFinal(input)
            override suspend fun resetConversation() = delegate.resetConversation()
            override fun close() = delegate.close()
        }
    }

    private data class ToolTrace(
        val turn: String,
        val tool: String,
        val arguments: JsonObject,
        val success: Boolean,
        val resultData: JsonObject?,
        val resultText: String,
    ) {
        fun toJson() = buildJsonObject {
            put("turn", turn); put("tool", tool); put("arguments", arguments)
            put("success", success); resultData?.let { put("result_data", it) }
            put("result", resultText)
        }
    }

    private class RecordingExecutor(private val delegate: ToolExecutor) : ToolExecutor {
        var currentTurn: String = "setup"
        val traces = mutableListOf<ToolTrace>()
        override suspend fun execute(
            call: com.hjp.agent.contract.ModelToolCall,
            snapshot: ToolCatalogSnapshot,
            context: ToolExecutionContext,
        ): ToolExecutionResult {
            val result = delegate.execute(call, snapshot, context)
            traces += ToolTrace(
                currentTurn,
                call.modelToolName,
                call.arguments,
                result is ToolExecutionResult.Success,
                (result as? ToolExecutionResult.Success)?.data,
                result.toString(),
            )
            return result
        }
    }

    private class CountingEmbeddingEngine(private val delegate: OnDeviceEmbeddingEngine) : EmbeddingEngine {
        var queryCalls = 0
            private set
        var documentCalls = 0
            private set
        override fun embed(input: String): FloatArray = embedQuery(input)
        override fun embedQuery(input: String): FloatArray {
            queryCalls += 1
            return delegate.embedQuery(input)
        }
        override fun embedDocument(input: String): FloatArray {
            documentCalls += 1
            if (documentCalls == 1 || documentCalls % 100 == 0) {
                Log.i(TAG, "DOCUMENT_EMBEDDING_PROGRESS count=$documentCalls")
            }
            return delegate.embedDocument(input)
        }
        override fun name(): String = delegate.name()
        override fun isModelBacked(): Boolean = delegate.isModelBacked
        override fun diagnosticStatus(): String = delegate.diagnosticStatus()
    }

    private class RecordingCalendarBackend : CalendarComposerBackend {
        val drafts = mutableListOf<CalendarDraft>()
        override fun isAvailable() = true
        override suspend fun open(draft: CalendarDraft): Boolean { drafts += draft; return true }
    }

    private class RecordingMessageBackend : MessageComposerBackend {
        val drafts = mutableListOf<MessageDraft>()
        override fun isAvailable(channel: MessageChannel?) = true
        override suspend fun open(draft: MessageDraft): Boolean { drafts += draft; return true }
    }

    private class A6Environment : AgentRuntimeEnvironment {
        override val localeTag = "ko-KR"
        override val timeZoneId = "Asia/Seoul"
        override suspend fun grantedPermissions(): Set<String> = emptySet()
        override suspend fun deviceCapabilities(): Set<String> = setOf(
            "android.external_ui", "contact.local_search", "contact.local_update", "datetime.current",
        )
        override suspend fun toolContext(sessionId: String, turnId: String) = ToolExecutionContext(
            sessionId = sessionId,
            turnId = turnId,
            localeTag = localeTag,
            deviceTimeZoneId = timeZoneId,
            confirmationGateway = ConfirmationGateway { true },
        )
    }

    private fun AgentSession.snapshotJson() = buildJsonObject {
        put("session_id", sessionId); put("generation", generation)
        put("transcript_entries", transcript.size)
        put("memory", toJson())
    }

    private fun AgentSession.toJson() = buildJsonObject {
        put("selected_card_id", conversationMemory.selectedContact?.cardId ?: "")
        put("selected_name", conversationMemory.selectedContact?.name ?: "")
        put("candidates", buildJsonArray {
            conversationMemory.candidateContacts.forEachIndexed { index, candidate ->
                add(buildJsonObject {
                    put("ordinal", index + 1); put("card_id", candidate.cardId)
                    put("name", candidate.name); put("company", candidate.company.orEmpty())
                    put("title", candidate.title.orEmpty())
                })
            }
        })
        put("mentions", buildJsonArray {
            conversationMemory.contactMentions.forEach { mention ->
                add(buildJsonObject {
                    put("card_id", mention.cardId); put("name", mention.name)
                    put("role", mention.role.name); put("order", mention.order); put("active", mention.active)
                })
            }
        })
        put("corrections", buildJsonArray { conversationMemory.corrections.forEach { add(it.content) } })
        put("actions", buildJsonArray {
            conversationMemory.actions.forEach { action ->
                add(buildJsonObject {
                    put("turn_id", action.turnId); put("status", action.status.name)
                    put("tools", buildJsonArray { action.executedTools.forEach { add(it) } })
                    put("outcome", action.outcomeType?.name ?: "")
                })
            }
        })
        put("capability_state", buildJsonObject {
            capabilityState.forEach { (key, value) -> put("${key.namespace}.${key.key}", value.value) }
        })
    }

    private companion object {
        fun decisionToJson(decision: ModelDecision) = when (decision) {
        is ModelDecision.ToolCalls -> buildJsonObject {
            put("type", "tool_calls")
            put("calls", buildJsonArray { decision.calls.forEach { call ->
                add(buildJsonObject {
                    put("name", call.modelToolName); put("arguments", call.arguments)
                })
            } })
        }
        is ModelDecision.FinalCandidate -> buildJsonObject {
            put("type", "final"); put("text", decision.draftText)
            put("clarification", decision.clarification?.name ?: "")
        }
        is ModelDecision.Invalid -> buildJsonObject {
            put("type", "invalid"); put("reason", decision.safeReason); put("retryable", decision.retryable)
        }
    }

    private fun MessageDraft.toJson() = buildJsonObject {
        put("channel", channel.name); put("to", to); put("subject", subject.orEmpty()); put("body", body)
    }

    private fun CalendarDraft.toJson() = buildJsonObject {
        put("title", title); put("start_millis", startMillis); put("end_millis", endMillis)
        put("location", location.orEmpty()); put("description", description.orEmpty())
        put("attendees", buildJsonArray { attendeeEmails.forEach { add(it) } })
    }

        const val TAG = "HjpContextA6"
        const val CARD_ASSET = "ryeong/cards_eval1000.json"
        const val FROZEN_CARDS_SHA256 =
            "f0feaebfdf5eb26c2a161a4b8c40d1307a6f5fa9c68f00309f05b69d03e7cd24"
        const val EXPECTED_CARDS = 1_000
        const val INITIAL_QUERY = "블루오션컨설팅 AI 개발자"
        val FALSE_SUCCESS_REGEX = Regex("(?:생성|작성|수정|완료|등록|저장)(?:했|됐|되었습니다|했습니다)")
        val PRETTY_JSON = Json { prettyPrint = true }

        fun sha256Static(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
