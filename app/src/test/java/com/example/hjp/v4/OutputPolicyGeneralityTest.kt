package com.example.hjp.v4

import com.example.hjp.eval.EvalOutputPolicy
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The output policy has to be a rule, not a list of the cycles that happened to exist when it was
 * written.
 *
 * Its first version enumerated protected roots up to `pre_device_v4_cycle8`. That protected the past
 * and left the present unguarded: cycle 9's own results were writable by anything, and cycle 10 would
 * have inherited the same hole one directory later. A default output of "the current cycle's `runs`
 * directory" has the same shape of problem — re-running overwrites the previous run's evidence, and
 * the fix expires the moment the next cycle starts.
 *
 * So the contract these tests pin has no cycle names in it at all:
 *
 *  - anything that already exists under the results root is read-only;
 *  - a run writes into a directory it created for that run, which must not already exist;
 *  - containment is decided on the canonical path, so `..`, an absolute path and a symlink cannot
 *    walk into a protected directory;
 *  - reading is unrestricted.
 */
class OutputPolicyGeneralityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resultsRoot = File("../tools/agent_eval/results")

    @Test
    fun `the policy names no cycle`() {
        // A rule that has to be edited every cycle will be forgotten one cycle.
        val source = File("src/test/java/com/example/hjp/eval/EvalOutputPolicy.kt")
        assertTrue("policy source must be readable at ${source.absolutePath}", source.exists())
        val cycleLiterals = Regex("cycle\\d+|pre_device_v\\d|pre_device_completion")
            .findAll(source.readText())
            .map { it.value }
            .filterNot { it == "pre_device_v4" && false }
            .toList()
        assertEquals(
            "the policy must not enumerate cycles or dataset versions; found $cycleLiterals",
            emptyList<String>(),
            cycleLiterals,
        )
    }

    @Test
    fun `every existing results directory is protected, including the newest`() {
        // Whatever exists today is history tomorrow. The newest cycle directory is the one an
        // enumerated list always misses.
        val existing = resultsRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        assertTrue("there should be existing result directories to protect", existing.isNotEmpty())
        val unprotected = existing.filterNot { EvalOutputPolicy.isProtected(it) }
        assertEquals(
            "every existing result directory must be read-only",
            emptyList<String>(),
            unprotected.map { it.name },
        )
    }

    @Test
    fun `an existing results file is protected`() {
        val someFile = resultsRoot.walkTopDown().firstOrNull { it.isFile }
        assertTrue("expected at least one existing result file", someFile != null)
        assertTrue(
            "${someFile!!.path} exists already, so it is read-only",
            EvalOutputPolicy.isProtected(someFile),
        )
    }

    @Test
    fun `a fresh run directory is unique and refuses to be reused`() {
        val first = EvalOutputPolicy.newRunDirectory("selftest")
        val second = EvalOutputPolicy.newRunDirectory("selftest")
        try {
            assertNotEquals(
                "two runs must not share a directory, or the second overwrites the first",
                first.canonicalPath,
                second.canonicalPath,
            )
            assertTrue(first.isDirectory && second.isDirectory)

            // Handing back a directory that already exists is the overwrite this prevents.
            val reuse = runCatching { EvalOutputPolicy.requireFreshRunDirectory(first) }
            assertTrue(
                "reusing an existing run directory must fail: ${reuse.getOrNull()}",
                reuse.isFailure,
            )
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test
    fun `path traversal cannot reach a protected directory`() {
        // Starts inside the repository, because a traversal from an unrelated temp directory would
        // resolve somewhere else entirely and prove nothing about containment.
        val inside = resultsRoot.listFiles()?.first { it.isDirectory }!!
        val traversal = File(inside, "../${inside.name}/subdir/report.json")
        assertTrue(
            "containment must be decided on the canonical path, not the literal one: " +
                traversal.path,
            EvalOutputPolicy.isProtected(traversal),
        )
    }

    @Test
    fun `an absolute path into a protected directory is refused`() {
        val absolute = resultsRoot.canonicalFile
        assertTrue(EvalOutputPolicy.isProtected(absolute))
        assertTrue(
            "requireWritable must throw for an absolute protected path",
            runCatching { EvalOutputPolicy.requireWritable(absolute) }.isFailure,
        )
    }

    @Test
    fun `a symlink into a protected directory is refused`() {
        val temp = temporaryFolder.newFolder("link-parent")
        val link = File(temp, "results-link")
        val target = resultsRoot.canonicalFile
        val created = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }
        if (created.isFailure) return // the filesystem does not allow it; nothing to assert

        assertTrue(
            "a symlink is a path into the protected tree once resolved",
            EvalOutputPolicy.isProtected(File(link, "pre_device_v3/anything.json")),
        )
    }

    @Test
    fun `a temporary directory is writable`() {
        val temp = temporaryFolder.newFolder("output")
        assertFalse(EvalOutputPolicy.isProtected(temp))
        assertEquals(temp, EvalOutputPolicy.requireWritable(temp))
    }

    @Test
    fun `reading a protected file is allowed`() {
        // The asymmetry is the point: history is readable, not writable.
        val someFile = resultsRoot.walkTopDown().firstOrNull { it.isFile }!!
        assertTrue("a protected file must still be readable", someFile.readBytes().isNotEmpty())
        assertTrue(EvalOutputPolicy.isProtected(someFile))
    }

    @Test
    fun `promotion into the results root refuses an existing destination`() {
        val source = temporaryFolder.newFile("evidence.json").apply { writeText("{}") }
        val existing = resultsRoot.walkTopDown().first { it.isFile }
        assertTrue(
            "promoting onto a file that already exists must fail",
            runCatching { EvalOutputPolicy.promote(source, existing) }.isFailure,
        )
    }
}
