package com.example.hjp

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.search.AndroidEmbeddingGemmaEngine
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.hjp.agent.core.ArtifactIdentification
import com.hjp.agent.core.ArtifactVerificationPolicy
import com.hjp.agent.core.CachingArtifactDigestProvider
import com.hjp.agent.core.ModelDeploymentResolver
import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device coexistence gate for the two production model stacks.
 *
 * This initializes the verified generative artifact and deliberately keeps that native Engine
 * open while the production Android EmbeddingGemma adapter and OnDeviceEmbeddingEngine wrapper
 * create a retrieval-query embedding. It does not create a generation session, run generation,
 * perform retrieval, or invoke a tool.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingGemmaCoexistenceInstrumentedTest {
    @Test
    fun verifiedArtifactsProduceNative768dQueryWhileGenerativeEngineIsOpen() = runBlocking {
        assumeTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
        assumeFalse(AppContainer.isAndroidEmulator())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val generativeRoot = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val generativeFile = File(generativeRoot, "hjp-agent.litertlm")
        val deployment = ModelDeploymentResolver.resolve(
            generativeFile,
            digestProvider = CachingArtifactDigestProvider(),
            verificationPolicy = ArtifactVerificationPolicy.REQUIRE_OFFICIAL_GENERATIVE_ARTIFACT,
        )
        assertEquals(ArtifactIdentification.KNOWN_ARTIFACT_VERIFIED, deployment.identifiedBy)
        assertEquals(
            ModelDeploymentResolver.OFFICIAL_GENERATIVE_ARTIFACT_SHA256,
            deployment.verifiedSha256,
        )
        assertTrue(deployment.usable)

        val internalModelDir = File(context.filesDir, "models")
        val embeddingFile = File(internalModelDir, AndroidEmbeddingGemmaEngine.MODEL_FILE_NAME)
        val tokenizerFile = File(internalModelDir, AndroidEmbeddingGemmaEngine.TOKENIZER_FILE_NAME)
        val cacheDirectory = File(
            context.cacheDir,
            "litertlm-embedding-coexist-0-13-1-${SystemClock.elapsedRealtimeNanos()}",
        )
        assertTrue(cacheDirectory.mkdirs() || cacheDirectory.isDirectory)

        var generativeEngine: Engine? = null
        var embeddingEngine: AndroidEmbeddingGemmaEngine? = null
        val startedAt = SystemClock.elapsedRealtime()
        logMemory(context, "before_native_initialization")
        Log.i(
            TAG,
            "COEXISTENCE_START pid=${Process.myPid()} generative_path=${generativeFile.absolutePath} " +
                "generative_sha256=${deployment.verifiedSha256}",
        )

        try {
            generativeEngine = Engine(
                EngineConfig(
                    modelPath = generativeFile.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = cacheDirectory.absolutePath,
                ),
            )
            generativeEngine.initialize()
            logMemory(context, "generative_initialized")
            Log.i(TAG, "GENERATIVE_ENGINE_HELD_OPEN pid=${Process.myPid()} litert_lm=0.13.1")

            embeddingEngine = AndroidEmbeddingGemmaEngine(context)
            assertTrue(embeddingEngine.diagnosticStatus(), embeddingEngine.isModelBacked)
            assertTrue(embeddingFile.isFile && embeddingFile.canRead())
            assertTrue(tokenizerFile.isFile && tokenizerFile.canRead())
            assertEquals(
                AndroidEmbeddingGemmaEngine.EXPECTED_MODEL_SHA256,
                sha256(embeddingFile),
            )
            assertEquals(
                AndroidEmbeddingGemmaEngine.EXPECTED_TOKENIZER_SHA256,
                sha256(tokenizerFile),
            )
            assertTrue(
                embeddingEngine.diagnosticStatus(),
                embeddingEngine.diagnosticStatus().contains("model=${embeddingFile.absolutePath}"),
            )
            logMemory(context, "embedding_initialized")
            Log.i(
                TAG,
                "EMBEDDING_ARTIFACT_VERIFIED model_path=${embeddingFile.absolutePath} " +
                    "model_bytes=${embeddingFile.length()} model_sha256=${sha256(embeddingFile)} " +
                    "tokenizer_path=${tokenizerFile.absolutePath} tokenizer_bytes=${tokenizerFile.length()} " +
                    "tokenizer_sha256=${sha256(tokenizerFile)}",
            )

            val onDevice = OnDeviceEmbeddingEngine.production(embeddingEngine)
            assertTrue(onDevice.diagnosticStatus(), onDevice.isModelBacked)
            assertFalse(onDevice.fallbackReason(), onDevice.isFallbackUsed)

            val queryStartedAt = SystemClock.elapsedRealtime()
            val vector = onDevice.embedQuery("온디바이스 명함 검색을 위한 의미 질의")
            val queryElapsed = SystemClock.elapsedRealtime() - queryStartedAt
            val norm = vector.fold(0.0) { total, value -> total + value * value }

            assertEquals(AndroidEmbeddingGemmaEngine.OUTPUT_DIMENSION, vector.size)
            assertTrue(vector.all(Float::isFinite))
            assertTrue(vector.any { it != 0f })
            assertTrue(norm.isFinite() && norm > 0.0)
            assertTrue(onDevice.diagnosticStatus(), onDevice.isModelBacked)
            assertFalse(onDevice.fallbackReason(), onDevice.isFallbackUsed)
            assertTrue(embeddingEngine.isModelBacked)
            logMemory(context, "native_query_completed")
            Log.i(
                TAG,
                "NATIVE_QUERY_EMBEDDING_VERIFIED pid=${Process.myPid()} dimension=${vector.size} " +
                    "l2_norm=${kotlin.math.sqrt(norm)} elapsed_ms=$queryElapsed " +
                    "wrapper=${onDevice.name()} fallback=false",
            )

            // Keep both native stacks resident long enough for host-side PID/memory/LMK checks.
            Log.i(TAG, "STABILITY_WINDOW_START pid=${Process.myPid()} duration_ms=$STABILITY_WINDOW_MILLIS")
            delay(STABILITY_WINDOW_MILLIS)
            assertTrue(embeddingEngine.isModelBacked)
            assertTrue(onDevice.isModelBacked)
            assertFalse(onDevice.isFallbackUsed)
            logMemory(context, "stability_window_complete")
            Log.i(
                TAG,
                "EMBEDDING_GENERATIVE_COEXISTENCE_VERIFIED pid=${Process.myPid()} elapsed_ms=" +
                    "${SystemClock.elapsedRealtime() - startedAt}",
            )
        } finally {
            embeddingEngine?.close()
            generativeEngine?.close()
            Log.i(TAG, "NATIVE_ENGINES_CLOSED pid=${Process.myPid()}")
        }
        Unit
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun logMemory(context: Context, stage: String) {
        val system = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        val process = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        Log.i(
            TAG,
            "MEMORY stage=$stage pid=${Process.myPid()} avail_bytes=${system.availMem} " +
                "total_bytes=${system.totalMem} threshold_bytes=${system.threshold} " +
                "low_memory=${system.lowMemory} total_pss_kb=${process.totalPss} " +
                "native_pss_kb=${process.nativePss} dalvik_pss_kb=${process.dalvikPss}",
        )
    }

    private companion object {
        const val TAG = "HjpEmbeddingCoexist"
        const val STABILITY_WINDOW_MILLIS = 15_000L
    }
}
