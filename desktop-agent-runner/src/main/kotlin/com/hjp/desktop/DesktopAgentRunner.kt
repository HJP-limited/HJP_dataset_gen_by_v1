package com.hjp.desktop

import com.example.hjp.LocalToolRoutingModelGateway
import com.hjp.agent.contract.AgentEvent
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.core.AgentKernel
import com.hjp.agent.core.AgentRuntimeEnvironment
import com.hjp.agent.core.AgentSessionManager
import com.hjp.agent.core.AgentTurnPolicy
import com.hjp.agent.core.DefaultToolExecutor
import com.hjp.agent.core.DefaultToolObservationMapper
import com.hjp.agent.core.DefaultToolPolicyEngine
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.InMemoryAgentSessionStore
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.agent.routing.AgentSystemInstructions
import com.hjp.agent.routing.RoutingFirstAgentModelGateway
import com.hjp.agent.litert.common.LiteRtNativeAgentModelGateway
import com.hjp.agent.litert.common.LiteRtNativeBackend
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.GetContactPlugin
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.ConfirmationGateway
import com.hjp.tool.contract.PermissionGateway
import com.hjp.tool.contract.ToolEventSink
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.datetime.GetCurrentDateTimePlugin
import com.hjp.tool.android.MessageDraftGenerator
import com.hjp.tool.android.TemplateMessageDraftGenerator
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.collect

class DesktopAgentRunner(
    private val config: DesktopConfig,
    modelAgentGatewayOverride: AgentModelGateway? = null,
    traceObserver: ((String, String) -> Unit)? = null,
    private val printOutput: Boolean = true,
    messageDraftGeneratorOverride: MessageDraftGenerator? = null,
) : AutoCloseable {
    private val logger = DesktopLogger(config.debug, traceObserver)
    init {
        logger.log(
            "MODEL_CONFIG",
            "model_id=${config.modelId} model_path=${config.modelFile.absolutePath} " +
                "model_size_bytes=${config.modelFile.takeIf { it.isFile }?.length() ?: -1}",
        )
    }
    private val dataSource = DesktopFileDataSource(config.cardDataFile)
    private val contactBackend = RyeongContactSearchBackend(dataSource)
    private val mockRecorder = DesktopMockActionRecorder()
    private val plugins = listOf(
        SearchContactsPlugin(contactBackend),
        GetContactPlugin(contactBackend),
        UpdateBusinessCardPlugin(dataSource, onUpdated = { contactBackend.invalidate() }),
        CreateCalendarEventPlugin(DesktopCalendarComposerBackend(mockRecorder, TimeZone.getDefault().id)),
        OpenComposePlugin(DesktopMessageComposerBackend(mockRecorder)),
        GetCurrentDateTimePlugin(),
    )
    private val registry = DefaultToolRegistry(plugins.map { ToolImplementationCandidate(it) })
    private val modelRunner = LiteRtLmProcessRunner(LiteRtProcessConfig(
        config.liteRtBin, config.modelFile, config.backend, config.timeoutMillis,
    ))
    private val routerGateway = LocalToolRoutingModelGateway(
        draftGenerator = messageDraftGeneratorOverride ?: when (config.mode) {
            RunnerMode.FULL -> SubprocessMessageDraftGenerator(modelRunner, config.modelId)
            else -> TemplateMessageDraftGenerator()
        },
        composeTrace = logger,
    )
    private val baseGateway: AgentModelGateway = when (config.mode) {
        RunnerMode.ROUTER_ONLY -> routerGateway
        RunnerMode.FULL -> RoutingFirstAgentModelGateway(
            primary = SubprocessLiteRtAgentModelGateway(modelRunner, logger),
            toolRouter = routerGateway,
            onPrimaryFailure = { _, error ->
                System.err.println("모델 gateway 오류 > ${error.message ?: error::class.java.simpleName}; deterministic router fallback을 사용합니다.")
            },
        )
        RunnerMode.MODEL_ONLY -> error("model-only는 AgentKernel을 만들지 않습니다.")
        RunnerMode.MODEL_AGENT -> modelAgentGatewayOverride ?: LiteRtNativeAgentModelGateway(
            modelId = config.modelId,
            modelFile = config.modelFile,
            cacheDirectory = config.cacheDirectory,
            backend = when (config.backend) {
                "cpu" -> LiteRtNativeBackend.CPU
                "gpu" -> LiteRtNativeBackend.GPU
                "npu" -> LiteRtNativeBackend.NPU
                else -> error("지원하지 않는 backend입니다: ${config.backend}")
            },
            strictContactGrounding = true,
            trace = logger,
        )
    }
    private val gateway = TracingAgentModelGateway(baseGateway, logger, config.mode, config.modelId)
    private val sessionManager = AgentSessionManager(
        InMemoryAgentSessionStore(), gateway, AgentSystemInstructions.HJP,
        Locale.getDefault().toLanguageTag(),
    )
    private val kernel = AgentKernel(
        registry = registry,
        toolExecutor = TracingToolExecutor(DefaultToolExecutor(registry), logger, mockRecorder),
        policyEngine = DefaultToolPolicyEngine(),
        sessionManager = sessionManager,
        observationMapper = DefaultToolObservationMapper(),
        environment = DesktopRuntimeEnvironment(),
        turnPolicy = AgentTurnPolicy(maxToolCalls = 5),
    )

    suspend fun validateData() {
        dataSource.loadAll()
    }

    /** Starts a fresh model conversation while retaining the initialized LiteRT-LM Engine. */
    suspend fun resetSession() {
        sessionManager.reset()
    }

    fun reportModelAvailability() {
        val error = if (config.mode == RunnerMode.MODEL_AGENT) {
            config.modelFile.takeUnless { it.isFile && it.canRead() }
                ?.let { LiteRtProcessException("모델 파일을 찾을 수 없습니다: ${it.absolutePath}") }
        } else {
            runCatching { modelRunner.validateFiles() }.exceptionOrNull()
        }
        error?.let {
            val suffix = if (config.mode == RunnerMode.FULL) {
                "; deterministic 요청은 계속 실행할 수 있습니다."
            } else ""
            System.err.println("모델 gateway 경고 > ${it.message}$suffix")
        }
    }

    suspend fun runPrompt(text: String, echoInput: Boolean = true): String {
        if (echoInput && printOutput) println("사용자 입력 > $text")
        logger.input(text)
        var final = ""
        kernel.runTurn(text).collect { event ->
            when (event) {
                is AgentEvent.FinalMessage -> final = event.text
                is AgentEvent.UserError -> final = event.messageKo
                else -> Unit
            }
        }
        logger.final(final)
        if (printOutput) println("최종 응답 > $final")
        return final
    }

    override fun close() = sessionManager.close()
}

private class DesktopRuntimeEnvironment : AgentRuntimeEnvironment {
    override val localeTag: String get() = Locale.getDefault().toLanguageTag()
    override val timeZoneId: String get() = TimeZone.getDefault().id
    override suspend fun grantedPermissions(): Set<String> = emptySet()
    override suspend fun deviceCapabilities(): Set<String> = setOf(
        "desktop.mock_external_ui", "contact.local_search", "contact.local_update", "datetime.current",
    )

    override suspend fun toolContext(sessionId: String, turnId: String) = ToolExecutionContext(
        sessionId = sessionId,
        turnId = turnId,
        localeTag = localeTag,
        deviceTimeZoneId = timeZoneId,
        permissionGateway = PermissionGateway { emptySet() },
        confirmationGateway = ConfirmationGateway { true },
        eventSink = ToolEventSink { },
    )
}
