package com.example.hjp

import android.content.Context
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
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
import com.hjp.agent.routing.AgentSystemInstructions
import com.hjp.tool.android.AndroidCalendarComposerBackend
import com.hjp.tool.android.AndroidMessageComposerBackend
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contact.GetContactPlugin
import com.hjp.tool.contact.RyeongContactSearchBackend
import com.hjp.tool.contact.SearchContactsPlugin
import com.hjp.tool.contract.ConfirmationGateway
import com.hjp.tool.contract.PermissionGateway
import com.hjp.tool.contract.ToolEventSink
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.datetime.GetCurrentDateTimePlugin
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val HJP_AGENT_MODEL_FILE_NAME: String get() = BuildConfig.HJP_MODEL_FILE_NAME
val HJP_AGENT_MODEL_SIZE_BYTES: Long get() = BuildConfig.HJP_MODEL_SIZE_BYTES
val HJP_AGENT_MODEL_SHA256: String get() = BuildConfig.HJP_MODEL_SHA256

class AppContainer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modelRoot = appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models")
    val modelFile: File = File(
        modelRoot,
        HJP_AGENT_MODEL_FILE_NAME.ifBlank { "ON_DEVICE_LLM_DISABLED" },
    )

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
    private val modelGateway = VariantModelGatewayFactory.create(
        modelFile = modelFile,
        cacheDirectory = File(appContext.cacheDir, "litertlm/${BuildConfig.HJP_MODEL_ID}"),
    )
    private val sessionManager = AgentSessionManager(
        InMemoryAgentSessionStore(), modelGateway, AgentSystemInstructions.HJP,
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

    val modelReady: Boolean get() =
        !BuildConfig.HJP_LITERT_ENABLED || isExpectedModelFile(modelFile)

    /**
     * A standalone APK carries the model as an uncompressed asset. LiteRT-LM requires a real
     * filesystem path, so install the asset into the app-specific model directory on first run.
     * Regular debug APKs do not contain this asset and continue to use the ADB deployment flow.
     */
    suspend fun prepareBundledModel(): Boolean = withContext(Dispatchers.IO) {
        if (!BuildConfig.HJP_LITERT_ENABLED) return@withContext true
        if (modelReady) return@withContext true

        val temporaryFile = File(modelRoot, "$HJP_AGENT_MODEL_FILE_NAME.part")
        try {
            modelRoot.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var copiedBytes = 0L
            appContext.assets.open(HJP_AGENT_MODEL_FILE_NAME).use { input ->
                FileOutputStream(temporaryFile).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copiedBytes += count
                    }
                }
            }

            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(copiedBytes == HJP_AGENT_MODEL_SIZE_BYTES) {
                "Bundled model size mismatch: expected $HJP_AGENT_MODEL_SIZE_BYTES, found $copiedBytes"
            }
            check(actualHash == HJP_AGENT_MODEL_SHA256) {
                "Bundled model SHA-256 mismatch: $actualHash"
            }

            if (modelFile.exists() && !modelFile.delete()) {
                error("Could not replace invalid model file: ${modelFile.absolutePath}")
            }
            check(temporaryFile.renameTo(modelFile)) {
                "Could not install bundled model: ${modelFile.absolutePath}"
            }
            isExpectedModelFile(modelFile)
        } catch (_: FileNotFoundException) {
            false
        } finally {
            if (temporaryFile.exists() && temporaryFile != modelFile) temporaryFile.delete()
        }
    }

    suspend fun resetSession() = sessionManager.reset()

    fun answerConfirmation(accepted: Boolean) = confirmationCoordinator.answer(accepted)

    override fun close() {
        sessionManager.close()
        database.close()
    }

    private companion object {
        fun isExpectedModelFile(file: File): Boolean =
            file.isFile && file.canRead() && file.length() == HJP_AGENT_MODEL_SIZE_BYTES
    }
}

private class AndroidAgentRuntimeEnvironment(
    private val confirmationCoordinator: AgentConfirmationCoordinator,
) : AgentRuntimeEnvironment {
    override val localeTag: String get() = Locale.getDefault().toLanguageTag()
    override val timeZoneId: String get() = TimeZone.getDefault().id
    override suspend fun grantedPermissions(): Set<String> = emptySet()
    override suspend fun deviceCapabilities(): Set<String> = setOf("android.external_ui", "contact.local_search", "contact.local_update", "datetime.current")

    override suspend fun toolContext(sessionId: String, turnId: String) = ToolExecutionContext(
        sessionId, turnId, localeTag, timeZoneId,
        permissionGateway = PermissionGateway { emptySet() },
        confirmationGateway = ConfirmationGateway { prompt -> confirmationCoordinator.confirm(prompt) },
        eventSink = ToolEventSink { },
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
