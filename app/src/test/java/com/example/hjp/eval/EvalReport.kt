package com.example.hjp.eval

import java.io.File

/** Minimal JSON writer, so results do not depend on a serialization runtime in unit tests. */
object EvalReport {

    /**
     * Where reports go when a caller does not say.
     *
     * This used to be `results/pre_device_completion`, a directory holding the record of a completed
     * evaluation, so `./gradlew test` rewrote historical results as a side effect of running. It now
     * points at the current cycle, and [EvalOutputPolicy] refuses any destination inside a historical
     * directory rather than letting a misconfigured run succeed quietly.
     */
    val RESULT_DIR: String get() = EvalOutputPolicy.outputDir(label = "reports").path

    /**
     * Writes [body] to [fileName], under [directory] when given.
     *
     * A test that wants its output isolated passes a temporary directory; an evidence run passes its
     * run-scoped path. Neither may be a historical directory.
     */
    fun write(fileName: String, body: String, directory: File? = null) {
        val target = File(EvalOutputPolicy.outputDir(directory), fileName)
        EvalOutputPolicy.requireWritable(target)
        target.parentFile?.mkdirs()
        target.writeText(body)
    }

    fun scenariosJson(
        suite: String,
        results: List<ScenarioResult>,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val byCategory = results.groupBy { it.primaryCategory }
        return buildString {
            append("{\n")
            append("  \"suite\": ").append(q(suite)).append(",\n")
            append("  \"environment\": ").append(obj(StrictMultiturnEvaluator.ENVIRONMENT)).append(",\n")
            extra.forEach { (k, v) -> append("  ").append(q(k)).append(": ").append(q(v)).append(",\n") }
            append("  \"total\": ").append(results.size).append(",\n")
            append("  \"task_success\": ").append(results.count { it.taskSuccess }).append(",\n")
            append("  \"strict_success\": ").append(results.count { it.strictSuccess }).append(",\n")
            append("  \"legacy_style_pass\": ").append(results.count { it.legacyPass }).append(",\n")
            append("  \"by_primary_category\": {\n")
            append(byCategory.entries.joinToString(",\n") { (name, list) ->
                "    ${q(name)}: {\"total\": ${list.size}, " +
                    "\"task_success\": ${list.count { it.taskSuccess }}, " +
                    "\"strict_success\": ${list.count { it.strictSuccess }}}"
            })
            append("\n  },\n")
            append("  \"turn_count_distribution\": ")
            append(obj(results.groupingBy { it.userTurnCount.toString() }.eachCount()
                .mapValues { it.value.toString() }.toSortedMap(compareBy { it.toInt() })))
            append(",\n")
            append("  \"scenarios\": [\n")
            append(results.joinToString(",\n") { scenarioJson(it) })
            append("\n  ]\n}\n")
        }
    }

    private fun scenarioJson(result: ScenarioResult): String = buildString {
        append("    {")
        append(q("id")).append(": ").append(q(result.id)).append(", ")
        append(q("primary_category")).append(": ").append(q(result.primaryCategory)).append(", ")
        append(q("secondary_tags")).append(": ").append(arr(result.secondaryTags)).append(", ")
        append(q("user_turn_count")).append(": ").append(result.userTurnCount).append(", ")
        append(q("task_success")).append(": ").append(result.taskSuccess).append(", ")
        append(q("strict_success")).append(": ").append(result.strictSuccess).append(", ")
        append(q("legacy_style_pass")).append(": ").append(result.legacyPass).append(", ")
        append(q("turns")).append(": [")
        append(result.turns.joinToString(", ") { turn ->
            "{${q("user")}: ${q(turn.user)}, ${q("act")}: ${q(turn.act)}, " +
                "${q("outcome")}: ${turn.outcome?.let(::q) ?: "null"}, " +
                "${q("tools")}: ${arr(turn.tools)}, " +
                "${q("selected_card_id")}: ${turn.selectedCardId?.let(::q) ?: "null"}, " +
                "${q("candidate_ids")}: ${arr(turn.candidateIds)}, " +
                "${q("side_effects")}: ${turn.sideEffects}, " +
                "${q("answer")}: ${q(turn.answer)}}"
        })
        append("], ")
        append(q("failures")).append(": [")
        append(result.failures.joinToString(", ") { failure ->
            "{${q("turn")}: ${failure.turnIndex}, ${q("kind")}: ${q(failure.kind)}, " +
                "${q("expected")}: ${q(failure.expected)}, ${q("actual")}: ${q(failure.actual)}, " +
                "${q("reason")}: ${q(failure.reason)}, ${q("strict_only")}: ${failure.strictOnly}}"
        })
        append("]}")
    }

    fun obj(values: Map<String, String>): String =
        values.entries.joinToString(", ", "{", "}") { "${q(it.key)}: ${q(it.value)}" }

    fun arr(values: List<String>): String = values.joinToString(", ", "[", "]") { q(it) }

    fun q(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("\t", " ") + "\""
}
