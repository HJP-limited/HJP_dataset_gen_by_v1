package com.example.hjp.eval

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visible suite, plus the checks that keep it from being padding.
 *
 * Environment: fake gateway (deterministic router as the model) over the real kernel, router, policy
 * engine, workflow and plugins. Nothing here says anything about Gemma's own behaviour.
 */
class VisibleGeneralizationRunnerTest {
    @Test
    fun `the visible suite is diverse, deduplicated and fully covered`() = runBlocking {
        val specs = VisibleGeneralizationCases.ALL
        val results = specs.map { StrictMultiturnEvaluator.evaluate(it) }

        EvalReport.write(
            "visible_generalization.json",
            EvalReport.scenariosJson("visible_generalization", results),
        )
        EvalReport.write("coverage_matrix.json", coverageMatrix(specs))

        val duplicates = duplicateReport(specs)
        EvalReport.write("visible_duplicates.json", duplicates.json())

        assertTrue("exact duplicate user scripts: ${duplicates.exact}", duplicates.exact.isEmpty())
        assertTrue(
            "normalized duplicate user scripts: ${duplicates.normalized}",
            duplicates.normalized.isEmpty(),
        )

        val failed = results.filterNot { it.strictSuccess }
        assertTrue(
            "strict failures ${failed.size}/${results.size}:\n" +
                failed.take(25).joinToString("\n") { result ->
                    "- ${result.id}: " + result.failures.joinToString("; ") {
                        "turn${it.turnIndex} ${it.kind} exp=${it.expected} act=${it.actual}"
                    }
                },
            failed.isEmpty(),
        )

        assertTrue("visible suite must hold at least 220 cases, got ${specs.size}", specs.size >= 220)
        EvalCategories.MINIMUMS.forEach { (category, minimum) ->
            val actual = specs.count { it.primaryCategory == category }
            assertTrue("$category has $actual cases, minimum $minimum", actual >= minimum)
        }
    }

    /** A case must count once, or a category minimum could be met by tagging alone. */
    @Test
    fun `every case has exactly one primary category`() {
        val categories = VisibleGeneralizationCases.ALL.map { it.primaryCategory }.toSet()
        assertTrue(
            "unknown primary categories: ${categories - EvalCategories.MINIMUMS.keys}",
            EvalCategories.MINIMUMS.keys.containsAll(categories),
        )
    }

    private data class Duplicates(
        val exact: List<String>,
        val normalized: List<String>,
        val templateSignatures: Map<String, Int>,
    ) {
        fun json(): String = buildString {
            append("{\n")
            append("  \"exact_duplicate_ids\": ").append(EvalReport.arr(exact)).append(",\n")
            append("  \"normalized_duplicate_ids\": ").append(EvalReport.arr(normalized)).append(",\n")
            append("  \"distinct_template_signatures\": ").append(templateSignatures.size).append(",\n")
            append("  \"largest_template_group\": ").append(templateSignatures.values.maxOrNull() ?: 0)
            append("\n}\n")
        }
    }

    private fun duplicateReport(specs: List<MultiturnSpec>): Duplicates {
        fun script(spec: MultiturnSpec) = spec.turns.joinToString("") { it.user }
        fun normalize(text: String) = text
            .replace(Regex("\\s+"), "")
            .replace(Regex("[.!?,]"), "")
            .lowercase()

        val byExact = specs.groupBy { script(it) }
        val byNormalized = specs.groupBy { normalize(script(it)) }
        return Duplicates(
            exact = byExact.filterValues { it.size > 1 }.values.flatten().map { it.id }.sorted(),
            normalized = byNormalized.filterValues { it.size > 1 }.values.flatten().map { it.id }.sorted(),
            templateSignatures = specs.groupingBy { it.templateSignature() }.eachCount(),
        )
    }

    private fun coverageMatrix(specs: List<MultiturnSpec>): String {
        val byCategory = specs.groupingBy { it.primaryCategory }.eachCount()
        val byTag = specs.flatMap { it.secondaryTags }.groupingBy { it }.eachCount()
        val byTurnCount = specs.groupingBy { bucket(it.userTurnCount) }.eachCount()
        val byToolChain = specs.groupingBy { chain(it) }.eachCount()
        return buildString {
            append("{\n")
            append("  \"total\": ").append(specs.size).append(",\n")
            append("  \"primary_category\": ").append(counts(byCategory)).append(",\n")
            append("  \"primary_category_minimums\": ").append(counts(EvalCategories.MINIMUMS)).append(",\n")
            append("  \"secondary_tags\": ").append(counts(byTag.toSortedMap())).append(",\n")
            append("  \"user_turn_buckets\": ").append(counts(byTurnCount.toSortedMap())).append(",\n")
            append("  \"tool_chains\": ").append(counts(byToolChain.toSortedMap())).append("\n")
            append("}\n")
        }
    }

    private fun bucket(turns: Int): String = when {
        turns <= 2 -> "2"
        turns <= 5 -> "3-5"
        turns <= 10 -> "6-10"
        turns <= 12 -> "11-12"
        turns <= 20 -> "13-20"
        else -> "21+"
    }

    private fun chain(spec: MultiturnSpec): String {
        val tools = spec.turns.flatMap { it.expectedTools }.distinct().sorted()
        return if (tools.isEmpty()) "none" else tools.joinToString("+")
    }

    private fun counts(values: Map<String, Int>): String =
        values.entries.joinToString(", ", "{", "}") { "${EvalReport.q(it.key)}: ${it.value}" }
}
