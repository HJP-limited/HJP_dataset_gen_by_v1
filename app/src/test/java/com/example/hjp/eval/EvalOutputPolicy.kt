package com.example.hjp.eval

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where an evaluation run may write.
 *
 * ## Why this is a rule rather than a list
 *
 * The first version of this object enumerated the result directories that existed when it was
 * written. That protected the past and left the present unguarded — the newest cycle's own results
 * were writable by anything, and the next cycle would inherit the same hole one directory later. A
 * default output of "the current cycle's runs directory" fails the same way: re-running overwrites
 * the previous run's evidence, and the fix expires when the cycle number changes.
 *
 * So there are no cycle names here. The rule is:
 *
 *  - **anything that already exists under the results root is read-only.** History is whatever is
 *    already on disk, which needs no maintenance to stay correct;
 *  - **a run writes into a directory it created for that run**, which must not already exist;
 *  - **containment is decided on the canonical path**, so `..`, an absolute path and a symlink all
 *    resolve before the check;
 *  - **reading is unrestricted.** The asymmetry is the point.
 *
 * Publishing a result into the results root is a separate, explicit step ([promote]) that refuses an
 * existing destination.
 */
object EvalOutputPolicy {

    /** The tree holding evaluation results. Everything already in it is read-only. */
    private val RESULTS_ROOT = File("../tools/agent_eval/results")

    private val runCounter = AtomicInteger(0)

    /**
     * True when writing to [target] would create or modify something inside the results tree that
     * this process did not create for the current run.
     *
     * A path that does not resolve into the results tree is not this object's business.
     */
    fun isProtected(target: File): Boolean {
        val root = canonical(RESULTS_ROOT) ?: return false
        val path = canonical(target) ?: return false
        if (path == root) return true
        if (!path.startsWith(root + File.separator)) return false
        // Inside the results tree: allowed only in a directory this run created.
        return canonicalPathsCreatedByThisRun.none { path == it || path.startsWith(it + File.separator) }
    }

    /**
     * Returns [target] when writing to it is allowed, and throws otherwise.
     *
     * An exception rather than a silent redirect: a run pointed at a historical directory has a
     * configuration bug, and quietly writing elsewhere hides it.
     */
    fun requireWritable(target: File): File {
        require(!isProtected(target)) {
            "refusing to write inside the evaluation results tree: ${canonical(target)}. " +
                "Existing results are read-only. Create a run directory with newRunDirectory(name), " +
                "pass an explicit output directory, or use a JUnit temporary directory."
        }
        return target
    }

    /**
     * Creates a directory for one run and returns it.
     *
     * The name carries the caller's label, the JVM start time and a counter, so two runs in the same
     * process and two processes started at different times all get distinct directories. Reusing a
     * directory is what silently replaced the previous run's evidence.
     */
    fun newRunDirectory(label: String): File {
        val safeLabel = label.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val stamp = jvmStartStamp
        while (true) {
            val candidate = File(RESULTS_ROOT, "$safeLabel/run_${stamp}_${runCounter.incrementAndGet()}")
            if (!candidate.exists()) {
                require(candidate.mkdirs()) { "could not create run directory ${candidate.path}" }
                canonical(candidate)?.let(canonicalPathsCreatedByThisRun::add)
                return candidate
            }
        }
    }

    /** Registers an externally supplied directory as this run's output, refusing an existing one. */
    fun requireFreshRunDirectory(directory: File): File {
        require(!directory.exists()) {
            "run directory ${directory.path} already exists; a run must not write over an earlier " +
                "run's evidence. Choose a new directory or use newRunDirectory(label)."
        }
        require(directory.mkdirs()) { "could not create run directory ${directory.path}" }
        canonical(directory)?.let(canonicalPathsCreatedByThisRun::add)
        return directory
    }

    /**
     * The output directory for a caller that did not supply one.
     *
     * Deliberately *not* a fixed path inside the results tree: each label gets a fresh run directory
     * the first time it is asked for, and the same one afterwards within this process. A later
     * process gets a different one, so nothing is ever overwritten.
     */
    @Synchronized
    fun outputDir(override: File? = null, label: String = "jvm"): File {
        if (override != null) return requireWritable(override)
        return defaultDirs.getOrPut(label) { newRunDirectory(label) }
    }

    /**
     * Publishes [source] to [destination] inside the results tree.
     *
     * The only sanctioned way anything enters the results tree, and it refuses to replace something
     * that is already there.
     */
    fun promote(source: File, destination: File) {
        require(source.exists()) { "nothing to promote at ${source.path}" }
        require(!destination.exists()) {
            "refusing to promote onto ${destination.path}, which already exists. Publishing must " +
                "never replace a recorded result."
        }
        destination.parentFile?.mkdirs()
        source.copyTo(destination)
    }

    // ---- internals ---------------------------------------------------------------------------------

    private val canonicalPathsCreatedByThisRun = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val defaultDirs = mutableMapOf<String, File>()
    private val jvmStartStamp: String by lazy {
        // Wall clock is fine here: this names a directory, it does not decide any evaluation outcome.
        java.time.Instant.ofEpochMilli(System.currentTimeMillis())
            .toString().replace(Regex("[^0-9]"), "").take(14)
    }

    private fun canonical(file: File): String? =
        runCatching { file.canonicalFile.path }.getOrNull()
}
