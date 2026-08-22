package com.example.hjp.eval

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Environment: fake gateway (the deterministic router stands in for the model) over the real kernel,
 * router, policy engine, workflow and plugins. These results say nothing about Gemma's own
 * behaviour; they verify the orchestration and safety layer.
 */
class KnownRegressionRunnerTest {
    @Test
    fun `every known regression holds strictly`() = runBlocking {
        val results = KnownRegressionCases.ALL.map { StrictMultiturnEvaluator.evaluate(it) }
        EvalReport.write(
            "known_regressions.json",
            EvalReport.scenariosJson("known_regressions", results),
        )

        val failed = results.filterNot { it.strictSuccess }
        assertTrue(
            "strict failures ${failed.size}/${results.size}:\n" + failed.joinToString("\n") { result ->
                "- ${result.id}: " + result.failures.joinToString("; ") {
                    "${it.kind} expected=${it.expected} actual=${it.actual}"
                }
            },
            failed.isEmpty(),
        )
        assertEquals(10, results.size)
    }
}
