package com.example.hjp

import android.content.Context
import android.os.Build
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.hjp.agent.contract.AgentModelGateway
import com.hjp.agent.core.AgentKernel
import com.hjp.agent.core.AgentTurnPolicy
import com.hjp.agent.core.AgentRuntimeEnvironment
import com.hjp.agent.core.AgentSessionManager
import com.hjp.agent.core.DefaultToolExecutor
import com.hjp.agent.core.DefaultToolObservationMapper
import com.hjp.agent.core.DefaultToolPolicyEngine
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.InMemoryAgentSessionStore
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.agent.litert.LiteRtAgentModelGateway
import com.hjp.agent.litert.LiteRtBackendPreference
import com.hjp.tool.android.AndroidCalendarComposerBackend
import com.hjp.tool.android.AndroidMessageComposerBackend
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contact.GetContactPlugin
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contract.ConfirmationGateway
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.datetime.GetCurrentDateTimePlugin
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred

class AppContainer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modelRoot = appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models")
    val modelFile: File = File(modelRoot, "hjp-agent.litertlm")

    private val confirmationCoordinator = AgentConfirmationCoordinator()
    private val database = HjpDatabase.getInstance(appContext)
    private val contactRepository = RoomBusinessCardRepository(appContext, database.businessCardDao())
    private val contactBackend = RyeongContactSearchBackend(contactRepository)
    private val plugins = listOf(
        SearchContactsPlugin(contactBackend),
        GetContactPlugin(contactBackend),
        UpdateBusinessCardPlugin(contactRepository, onUpdated = { contactBackend.invalidate() }),
        CreateCalendarEventPlugin(AndroidCalendarComposerBackend(appContext)),
        OpenComposePlugin(AndroidMessageComposerBackend(appContext)),
        GetCurrentDateTimePlugin(),
    )
    private val registry = DefaultToolRegistry(plugins.map { ToolImplementationCandidate(it) })
    private val emulatorCompatibilityMode = isAndroidEmulator()
    private val modelGateway: AgentModelGateway = if (emulatorCompatibilityMode) {
        LocalToolRoutingModelGateway()
    } else {
        LiteRtAgentModelGateway(
            modelFile,
            File(appContext.cacheDir, "litertlm"),
            LiteRtBackendPreference.CPU_ONLY,
        )
    }
    private val sessionManager = AgentSessionManager(
        InMemoryAgentSessionStore(), modelGateway, SYSTEM_INSTRUCTION,
        Locale.getDefault().toLanguageTag(),
    )

    val kernel = AgentKernel(
        registry = registry,
        toolExecutor = DefaultToolExecutor(registry),
        policyEngine = DefaultToolPolicyEngine(),
        sessionManager = sessionManager,
        observationMapper = DefaultToolObservationMapper(),
        environment = AndroidAgentRuntimeEnvironment(confirmationCoordinator),
        turnPolicy = AgentTurnPolicy(maxToolCalls = 5),
    )

    val modelReady: Boolean get() = emulatorCompatibilityMode || (modelFile.isFile && modelFile.canRead())

    suspend fun resetSession() = sessionManager.reset()

    fun answerConfirmation(accepted: Boolean) = confirmationCoordinator.answer(accepted)

    override fun close() {
        sessionManager.close()
        database.close()
    }

    private companion object {
        const val SYSTEM_INSTRUCTION = """
You are a model that can do function calling with the following functions
당신은 Android 기기 안에서만 동작하는 HJP 명함 에이전트입니다.
한국어로 간결하고 정확하게 답하세요. 현재 제공된 native tool 목록에 있는 기능만 사용하세요.
연락처는 search_contacts로 찾고 필요할 때만 get_contact로 상세정보를 조회하세요.
명함을 수정하려면 update_business_card를 사용하되 대상 명함을 먼저 특정하세요.
캘린더와 메시지 도구는 외부 작성 화면만 엽니다. 저장되었다거나 전송되었다고 말하지 마세요.
상대 날짜 일정은 get_current_datetime으로 현재 날짜·시각을 확인한 뒤 절대 시각으로 변환해서 create_calendar_event에 전달하세요.
"""

        fun isAndroidEmulator(): Boolean =
            Build.HARDWARE.equals("ranchu", ignoreCase = true) ||
                Build.HARDWARE.equals("goldfish", ignoreCase = true) ||
                Build.MODEL.startsWith("sdk_gphone", ignoreCase = true) ||
                Build.PRODUCT.contains("sdk_gphone", ignoreCase = true) ||
                Build.FINGERPRINT.startsWith("generic", ignoreCase = true)
    }
}

private class AndroidAgentRuntimeEnvironment(
    private val confirmationCoordinator: AgentConfirmationCoordinator,
) : AgentRuntimeEnvironment {
    override suspend fun toolContext(sessionId: String, turnId: String) = ToolExecutionContext(
        sessionId, turnId, TimeZone.getDefault().id,
        confirmationGateway = ConfirmationGateway { prompt -> confirmationCoordinator.confirm(prompt) },
    )
}

private class AgentConfirmationCoordinator {
    private var pending: CompletableDeferred<Boolean>? = null

    suspend fun confirm(promptKo: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        synchronized(this) {
            pending?.complete(false)
            pending = deferred
        }
        return try {
            deferred.await()
        } finally {
            synchronized(this) {
                if (pending === deferred) pending = null
            }
        }
    }

    fun answer(accepted: Boolean) {
        synchronized(this) {
            pending?.complete(accepted)
            pending = null
        }
    }
}
