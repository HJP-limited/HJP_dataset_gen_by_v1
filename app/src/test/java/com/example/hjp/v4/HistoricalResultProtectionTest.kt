package com.example.hjp.v4

import com.example.hjp.eval.EvalOutputPolicy
import com.example.hjp.eval.EvalReport
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Running the test suite must not edit the record of earlier evaluations.
 *
 * It used to. `EvalReport` defaulted to `results/pre_device_completion`, `MultiturnCaseRunnerTest`
 * wrote `results/multiturn_cases.json` by absolute path, and a cycle 7 artefact was rewritten during a
 * cycle 8 run. Four historical files changed that way. Nothing failed, nothing warned; the only
 * reason it surfaced is that a snapshot of the previous cycle existed to compare against.
 *
 * These tests do not write into a protected directory to prove the point — doing so would cause the
 * very damage being guarded against. They check the *resolved destinations* and the guard instead:
 * where a writer would write, whether the guard refuses it, and whether the protected files still
 * hash to what they hashed before the suite ran.
 */
class HistoricalResultProtectionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** The four files a cycle 8 run was found to have modified, plus the official v3 record. */
    private val protectedFiles = listOf(
        "../tools/agent_eval/results/pre_device_completion/frozen_heldout/heldout_results_fake.json",
        "../tools/agent_eval/results/pre_device_completion/visible_generalization.json",
        "../tools/agent_eval/results/multiturn_cases.json",
        "../tools/agent_eval/results/pre_device_v5_cycle7/contract/legacy_outcome_translations_observed.json",
        "../tools/agent_eval/results/pre_device_v3/heldout_v3/heldout_v3_validation.json",
        "../tools/agent_eval/results/pre_device_v3/heldout_v3/heldout_v3_results.scored_run.json",
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `no report writer resolves to a protected directory`() {
        // The characterization of the defect: EvalReport's default destination was itself a historical
        // directory, so every suite that used it rewrote one.
        val destination = File(EvalReport.RESULT_DIR)
        assertFalse(
            "EvalReport writes to ${destination.absolutePath}, which is a protected historical " +
                "result directory. A run must write to a per-run directory instead.",
            EvalOutputPolicy.isProtected(destination),
        )
    }

    @Test
    fun `the guard refuses every protected root and file`() {
        val shouldBeRefused = listOf(
            "../tools/agent_eval/results/pre_device_completion",
            "../tools/agent_eval/results/pre_device_completion/frozen_heldout/heldout_results_fake.json",
            "../tools/agent_eval/results/pre_device_v2/heldout_v2/heldout_v2_results.json",
            "../tools/agent_eval/results/pre_device_v3/heldout_v3/heldout_v3_validation.json",
            "../tools/agent_eval/results/pre_device_v4/replay/heldout_v3/heldout_v3_results.json",
            "../tools/agent_eval/results/pre_device_v5_cycle7/contract/semantic.json",
            "../tools/agent_eval/results/pre_device_v4_cycle8/jvm/run_2_final/junit_summary.json",
            "../tools/agent_eval/results/multiturn_cases.json",
        )
        val notRefused = shouldBeRefused.filterNot { EvalOutputPolicy.isProtected(File(it)) }
        assertEquals(
            "every historical result path must be refused, including new subdirectories inside one",
            emptyList<String>(),
            notRefused,
        )
        shouldBeRefused.forEach { path ->
            val failure = runCatching { EvalOutputPolicy.requireWritable(File(path)) }.exceptionOrNull()
            // The contract is: refuse, with an explanation naming the path and the reason. The exact
            // wording is not the contract, so this checks the parts a reader needs.
            assertTrue(
                "requireWritable must throw for $path",
                failure is IllegalArgumentException,
            )
            assertTrue(
                "the refusal must say what it refused and why: ${failure?.message}",
                failure!!.message!!.contains("refusing to write") &&
                    failure.message!!.contains("read-only"),
            )
        }
    }

    @Test
    fun `the guard allows this cycle's directory and a temporary directory`() {
        // A guard that refuses everything is no more useful than one that refuses nothing.
        listOf(
            EvalOutputPolicy.outputDir(),
            EvalOutputPolicy.outputDir(),
            temporaryFolder.newFolder("output"),
        ).forEach { allowed ->
            assertFalse(
                "${allowed.path} must be writable",
                EvalOutputPolicy.isProtected(allowed),
            )
            assertEquals(allowed, EvalOutputPolicy.requireWritable(allowed))
        }
    }

    @Test
    fun `an explicit output directory is honoured over the default`() {
        val explicit = temporaryFolder.newFolder("explicit")
        assertEquals(explicit, EvalOutputPolicy.outputDir(explicit))
        assertFalse(EvalOutputPolicy.isProtected(EvalOutputPolicy.outputDir()))
    }

    @Test
    fun `the protected files still hash to their recorded values`() {
        // A live check that this suite has not modified them. The expected values are read from the
        // protection manifest written at the start of the cycle, so this test compares the tree
        // against a record rather than against itself.
        val manifest = File("../tools/agent_eval/results/pre_device_v4_cycle9/protection/protected_manifest.json")
        if (!manifest.exists()) return

        val recorded = Regex("\"([^\"]+)\"\\s*:\\s*\"([0-9a-f]{64})\"")
            .findAll(manifest.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

        val changed = protectedFiles.mapNotNull { relative ->
            val file = File(relative)
            if (!file.exists()) return@mapNotNull null
            val key = relative.removePrefix("../")
            val expected = recorded[key] ?: return@mapNotNull null
            val actual = sha256(file)
            if (actual == expected) null else "$key expected $expected actual $actual"
        }
        assertEquals(
            "a test run must not change a historical result file",
            emptyList<String>(),
            changed,
        )
    }
}
