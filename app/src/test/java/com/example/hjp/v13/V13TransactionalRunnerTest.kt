package com.example.hjp.v13

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unit checks whose result the v13 durable attestation records.
 *
 * v12 ran an equivalent set and passed, but the only evidence was the Gradle XML Gradle owns, and
 * by final preflight time it had been replaced. The tests here are the same kind of check; what is
 * new is that `tools/ryeong_official_v13/durable_unit_attestation_v13.py` seals their result into a
 * document outside `build/` that the final freeze pins.
 */
class V13TransactionalRunnerTest {
    @Test fun `generated identity is the canonical K13 view`() {
        assertEquals("K13", V13RunIdentity.K13.RUN_KEY)
        assertTrue(V13RunIdentity.CANONICAL_FIELDS.contains("output_namespace"))
        assertFalse(V13RunIdentity.CANONICAL_FIELDS.contains("host_output_namespace"))
        assertEquals(64, V13RunIdentity.REGISTRY_DIGEST.length)
        assertEquals(listOf("K3", "K4", "K5", "K6", "K7", "K8", "K9", "K10", "K11", "K12", "K13",
            "D13", "V13_SMOKE"), V13RunIdentity.RUN_ORDER)
    }

    @Test fun `registry declares the device and smoke runs separately from the host run`() {
        assertEquals("host", V13RunIdentity.K13.RUN_KIND)
        assertEquals("device_official", V13RunIdentity.D13.RUN_KIND)
        assertEquals("device_smoke", V13RunIdentity.V13_SMOKE.RUN_KIND)
        val namespaces = listOf(V13RunIdentity.K13.OUTPUT_NAMESPACE,
            V13RunIdentity.D13.OUTPUT_NAMESPACE, V13RunIdentity.V13_SMOKE.OUTPUT_NAMESPACE)
        assertEquals(namespaces.size, namespaces.toSet().size)
        val markers = listOf(V13RunIdentity.K13.MARKER_PATH,
            V13RunIdentity.D13.MARKER_PATH, V13RunIdentity.V13_SMOKE.MARKER_PATH)
        assertEquals(markers.size, markers.toSet().size)
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
        assertTrue(claim < directory && directory < partial && partial < raw &&
            raw < result && result < status && status < remove)
    }

    @Test fun `official identity override vocabulary is absent`() {
        val source = source()
        assertFalse(source.contains("RYEONG_V13_OFFICIAL_OUT"))
        assertFalse(source.contains("ryeongV13OfficialOut"))
        assertTrue(source.contains("V13RunIdentity.K13.OUTPUT_NAMESPACE"))
    }

    @Test fun `create new and partial retention work on absent namespace`() {
        val root = Files.createTempDirectory("v13-kotlin-lifecycle-")
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

    @Test fun `K12 absence is an explicit precondition`() {
        val source = source()
        assertTrue(source.contains("assertRunAbsent(K12_STATUS, K12_MARKER)"))
        assertTrue(source.contains("ryeong_official_v12/run_authority/jvm_keyword/run_status.json"))
        assertEquals("NOT_YET_EXECUTED", V13RunIdentity.K12.EXPECTED_PRE_RUN_STATE)
    }

    @Test fun `the protected authority the runner reads is bound to the pre-run phase`() {
        val source = source()
        assertTrue(source.contains("PROTECTED_INTEGRITY_pre_k13.json"))
        assertTrue(source.contains("\\\"stage\\\": \\\"pre_k13\\\""))
        assertFalse(source.contains("PROTECTED_INTEGRITY_candidate.json"))
    }

    @Test fun `the approved quarantine path is asserted absent at admission`() {
        val source = source()
        assertTrue(source.contains("QUARANTINE_EXACT_PATH"))
        assertTrue(source.contains("integration_evidence/evaluation/ryeong_official_v10/.DS_Store"))
        // Exactly one path, matched literally. No wildcard, no suffix test, no directory sweep.
        assertFalse(source.contains("*.DS_Store"))
        assertFalse(source.contains("endsWith(\".DS_Store\")"))
    }

    @Test fun `every earlier official and device namespace is forbidden`() {
        val source = source()
        listOf("ryeong_official_v10", "ryeong_official_v11", "ryeong_official_v12",
            "ryeong_device_eval_v12").forEach {
            assertTrue("forbidden list is missing $it", source.contains("\"$it\""))
        }
        assertTrue(source.contains("segment == older"))
    }

    private fun source() = java.io.File(
        requireNotNull(generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "settings.gradle.kts").isFile }),
        "app/src/test/java/com/example/hjp/eval/v13/RyeongV13OfficialRunTest.kt",
    ).readText()
}
