package com.example.hjp

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.hjp.agent.core.ArtifactIdentification
import com.hjp.agent.core.ArtifactVerificationPolicy
import com.hjp.agent.core.CachingArtifactDigestProvider
import com.hjp.agent.core.ModelDeploymentResolver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Initialization-only physical-device gate. It never creates a conversation or runs generation. */
@RunWith(AndroidJUnit4::class)
class LiteRtInitializationInstrumentedTest {
    @Test
    fun verifiedOfficialArtifactInitializesLiteRtLmCpuEngine() {
        runBlocking {
            assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
            assumeFalse(AppContainer.isAndroidEmulator())

            val context = ApplicationProvider.getApplicationContext<Context>()
            val modelRoot = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
            val modelFile = File(modelRoot, "hjp-agent.litertlm")
            val deployment = ModelDeploymentResolver.resolve(
                modelFile,
                digestProvider = CachingArtifactDigestProvider(),
                verificationPolicy = ArtifactVerificationPolicy.REQUIRE_OFFICIAL_GENERATIVE_ARTIFACT,
            )

            assertEquals(
                deployment.diagnosticSummary().toString(),
                ArtifactIdentification.KNOWN_ARTIFACT_VERIFIED,
                deployment.identifiedBy,
            )
            assertEquals(
                ModelDeploymentResolver.OFFICIAL_GENERATIVE_ARTIFACT_ID,
                deployment.artifactId,
            )
            assertEquals(
                ModelDeploymentResolver.OFFICIAL_GENERATIVE_ARTIFACT_SHA256,
                deployment.verifiedSha256,
            )
            assertTrue(deployment.usable)

            val memoryBefore = memoryInfo(context)
            val cacheDirectory = File(
                context.cacheDir,
                "litertlm-init-verification-0-13-1-${SystemClock.elapsedRealtimeNanos()}",
            )
            assertTrue(cacheDirectory.mkdirs() || cacheDirectory.isDirectory)
            Log.i(
                TAG,
                "INITIALIZE_START pid=${Process.myPid()} artifact=${deployment.artifactId} " +
                    "bytes=${deployment.actualSizeBytes} sha256=${deployment.verifiedSha256} " +
                    "cache=${cacheDirectory.absolutePath} avail_mem=${memoryBefore.availMem} " +
                    "low_memory=${memoryBefore.lowMemory}",
            )

            val startedAt = SystemClock.elapsedRealtime()
            var engine: Engine? = null
            try {
                engine = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = cacheDirectory.absolutePath,
                    ),
                )
                engine.initialize()
                val memoryAfter = memoryInfo(context)
                Log.i(
                    TAG,
                    "INITIALIZE_SUCCESS pid=${Process.myPid()} elapsed_ms=" +
                        "${SystemClock.elapsedRealtime() - startedAt} avail_mem=${memoryAfter.availMem} " +
                        "low_memory=${memoryAfter.lowMemory}",
                )
            } finally {
                engine?.close()
                Log.i(TAG, "ENGINE_CLOSED pid=${Process.myPid()}")
            }
        }
    }

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also { info ->
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        }

    private companion object {
        const val TAG = "HjpLiteRtInitVerify"
    }
}
