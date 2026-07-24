package com.hjp.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopConfigModelSelectionTest {
    @Test
    fun `explicit Gemma 4 model path and id are preserved`() {
        val path = "/tmp/models/Gemma 4/gemma-4-E2B-it.litertlm"
        val config = DesktopConfig.from(arrayOf(
            "--model", path,
            "--model-id", "gemma4-e2b-it",
        ), emptyMap())

        assertEquals("gemma4-e2b-it", config.modelId)
        assertEquals(File(path), config.modelFile)
        assertTrue(config.cacheDirectory.path.endsWith("litertlm/gemma4-e2b-it"))
    }

    @Test
    fun `Gemma 4 path infers model id when id is omitted`() {
        val config = DesktopConfig.from(
            arrayOf("--model", "/tmp/gemma-4-E2B-it.litertlm"),
            emptyMap(),
        )

        assertEquals("gemma4-e2b-it", config.modelId)
    }

    @Test
    fun `model id without path resolves its own filename and never Gemma 3`() {
        val config = DesktopConfig.from(
            arrayOf("--model-id", "gemma4-e2b-it"),
            emptyMap(),
        )

        assertEquals("gemma-4-E2B-it.litertlm", config.modelFile.name)
    }

    @Test
    fun `unsupported explicit model id fails clearly`() {
        val error = runCatching {
            DesktopConfig.from(arrayOf("--model-id", "unknown-model"), emptyMap())
        }.exceptionOrNull()

        assertTrue(requireNotNull(error).message.orEmpty().contains("지원하지 않는 model-id"))
    }
}
