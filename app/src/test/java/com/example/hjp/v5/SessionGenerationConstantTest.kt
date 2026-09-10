package com.example.hjp.v5

import com.example.hjp.MultiturnScenarioHarness
import com.example.hjp.eval.ryeong2.RyeongV2Runner
import com.example.hjp.eval.v5.RyeongV5OfficialRunTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The generation RUN_K5 records is measured, not assumed.
 *
 * The v5 contact-dependency rule refuses a verification from an older session generation, so the
 * generation each turn carries is part of the judgement. `RyeongV5OfficialRunTest` records a constant
 * for every host turn, because [RyeongV2Runner] builds one `MultiturnScenarioHarness` per scenario —
 * each with its own `InMemoryAgentSessionStore` — and never calls `replace()`.
 *
 * A constant written into a run record on the strength of a comment is a fact nobody checked. So
 * this drives the real harness over a multi-turn scenario and reads the generation off the live
 * session, and separately proves the generation *does* move when the session is replaced — otherwise
 * "it is always 0" would be true for the uninteresting reason that nothing ever changes it.
 */
class SessionGenerationConstantTest {

    private val script = listOf(
        "김지원 회사 알려줘",
        "이메일 주소는?",
        "직책은?",
        "그 사람한테 메일 초안 써줘",
        "내일 3시에 일정 잡아줘",
        "박민수는?",
    )

    @Test
    fun `every turn of a scenario carries the generation the run records`() {
        val harness = MultiturnScenarioHarness()
        try {
            runBlocking {
                assertEquals(
                    "a fresh session store starts at the recorded generation",
                    RyeongV5OfficialRunTest.HOST_SESSION_GENERATION,
                    harness.session().generation,
                )
                script.forEachIndexed { index, sentence ->
                    harness.turn(sentence)
                    assertEquals(
                        "after turn ${index + 1} ('$sentence')",
                        RyeongV5OfficialRunTest.HOST_SESSION_GENERATION,
                        harness.session().generation,
                    )
                }
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `replacing the session does move the generation`() {
        val harness = MultiturnScenarioHarness()
        try {
            runBlocking {
                harness.turn(script.first())
                val before = harness.session().generation
                harness.reset()
                val after = harness.session().generation
                assertNotEquals("새 대화 must change the generation", before, after)
                assertEquals("and it moves by exactly one", before + 1, after)
            }
        } finally {
            harness.close()
        }
    }
}
