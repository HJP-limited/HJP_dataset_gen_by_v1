package com.hjp.desktop

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

interface RealModelSmokeTest

@Category(RealModelSmokeTest::class)
class RealLiteRtModelSmokeTest {
    @Test
    fun `real LiteRT-LM model emits non-empty output`() {
        val home = File(System.getProperty("user.home"))
        val root = projectRoot()
        val executable = File(System.getenv("LITERT_LM_BIN") ?: File(home, "litert-lm-env/bin/litert-lm").path)
        val model = File(System.getenv("LITERT_LM_MODEL") ?: File(root, "models/gemma3-1b-it-int4.litertlm").path)
        val output = LiteRtLmProcessRunner(LiteRtProcessConfig(
            executable, model, System.getenv("LITERT_LM_BACKEND") ?: "cpu", 900_000,
        )).generate("한 문장으로 인사해 주세요.")
        assertTrue(output.generatedText.isNotBlank())
    }

    @Test
    fun `real model-agent invokes native model without deterministic router`() = runBlocking {
        val root = projectRoot()
        val traces = mutableListOf<Pair<String, String>>()
        val config = DesktopConfig.from(arrayOf(
            "--mode", "model-agent",
            "--prompt", "김지원 명함을 찾아줘.",
        ))
        DesktopAgentRunner(
            config = config,
            traceObserver = { stage, message -> traces += stage to message },
            printOutput = false,
        ).use { runner ->
            runner.validateData()
            val final = runner.runPrompt(requireNotNull(config.prompt))
            assertTrue(final.isNotBlank())
        }

        assertTrue(traces.any { it.first == "ROUTING_POLICY" && it.second.contains("bypassed=true") })
        assertTrue(traces.any { it.first == "MODEL_REQUEST" })
        assertTrue(traces.any { it.first == "MODEL_RAW_OUTPUT" })
        assertTrue(traces.any { it.first == "MODEL_DECISION_SOURCE" })
        assertFalse(traces.any { it.first == "ROUTER" })
    }

    private fun projectRoot(): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        error("project root not found")
    }
}
