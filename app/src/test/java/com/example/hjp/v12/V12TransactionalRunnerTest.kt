package com.example.hjp.v12

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V12TransactionalRunnerTest {
    @Test fun `generated identity is canonical K12 view`() {
        assertEquals("K12", V12RunIdentity.K12.RUN_KEY)
        assertEquals("output_namespace", V12RunIdentity.CANONICAL_FIELDS[2])
        assertFalse(V12RunIdentity.CANONICAL_FIELDS.contains("host_output_namespace"))
        assertEquals(64, V12RunIdentity.REGISTRY_DIGEST.length)
    }

    @Test fun `runner orders claim directory partial and finalization`() {
        val source = source()
        val claim = source.indexOf("StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE")
        val directory = source.indexOf("Files.createDirectories(dir.toPath())")
        val partial = source.indexOf("Files.newOutputStream(partial.toPath(), StandardOpenOption.CREATE_NEW")
        val raw = source.indexOf("Files.createLink(finalRaw.toPath(), partial.toPath())")
        val result = source.indexOf("transition(stateTrace, \"RESULT_FINALIZED\")")
        val status = source.indexOf("transition(stateTrace, \"STATUS_FINALIZED\")")
        val remove = source.indexOf("partial.delete()")
        assertTrue(listOf(claim, directory, partial, raw, result, status, remove).all { it >= 0 })
        assertTrue(claim < directory && directory < partial && partial < raw && raw < result && result < status && status < remove)
    }

    @Test fun `official identity override vocabulary is absent`() {
        val source = source()
        assertFalse(source.contains("RYEONG_V12_OFFICIAL_OUT"))
        assertFalse(source.contains("ryeongV12OfficialOut"))
        assertTrue(source.contains("V12RunIdentity.K12.OUTPUT_NAMESPACE"))
    }

    @Test fun `create new and partial retention work on absent namespace`() {
        val root = Files.createTempDirectory("v12-kotlin-lifecycle-")
        val marker = root.resolve("execution/invocation.marker")
        val dir = root.resolve("result")
        assertFalse(dir.toFile().exists())
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "one", StandardOpenOption.CREATE_NEW)
        Files.createDirectories(dir)
        val partial = dir.resolve("raw_turns.jsonl.partial")
        Files.writeString(partial, "raw\n", StandardOpenOption.CREATE_NEW)
        val raw = dir.resolve("raw_turns.jsonl")
        Files.createLink(raw, partial)
        Files.writeString(dir.resolve("result.json"), "{}", StandardOpenOption.CREATE_NEW)
        Files.writeString(dir.resolve("run_status.json"), "{}", StandardOpenOption.CREATE_NEW)
        assertEquals("raw\n", raw.readText())
        Files.delete(partial)
        assertFalse(partial.toFile().exists())
        var refused = false
        try {
            Files.writeString(marker, "two", StandardOpenOption.CREATE_NEW)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test fun `K11 preservation is an explicit precondition`() {
        val source = source()
        assertTrue(source.contains("assertK11PartialUnchanged()"))
        assertTrue(source.contains("6deb7ab47a9502771378340afcf3c3789708840ce534c375278c78c895a41560"))
    }

    private fun source() = java.io.File(
        requireNotNull(generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "settings.gradle.kts").isFile }),
        "app/src/test/java/com/example/hjp/eval/v12/RyeongV12OfficialRunTest.kt",
    ).readText()
}
