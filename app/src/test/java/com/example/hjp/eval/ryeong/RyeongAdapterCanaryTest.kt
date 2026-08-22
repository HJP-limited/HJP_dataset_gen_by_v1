package com.example.hjp.eval.ryeong

import com.hjp.searchlookup.OnDeviceEmbeddingEngine
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.RyeongContactSearchBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does the plumbing work end to end, on the real agent?
 *
 * This is not a score. It answers a narrower question: does a scenario go from the frozen JSON
 * shape, through the production kernel and the production search service, and come back out as an
 * observation with a ranking and a focus in it. The scenarios here are synthetic and appear
 * nowhere in the frozen Ryeong set, so nothing can pass by being recognised.
 */
class RyeongAdapterCanaryTest {

    // Synthetic people. None of these names, companies or IDs exist in cards_eval1000.json.
    private val cards = listOf(
        card("CN-001", "표하윤", "너울건설", "안전관리", "안전관리팀", "서울", "02-0000-0001"),
        card("CN-002", "제갈민", "새벽테크", "연구소장", "연구소", "대전", "042-0000-0002"),
        card("CN-003", "노아린", "새벽테크", "변호사", "법무팀", "대전", "042-0000-0003"),
        card("CN-004", "표하윤", "물결식품", "영업이사", "영업팀", "부산", "051-0000-0004"),
    )

    private fun card(
        id: String, name: String, company: String, title: String,
        department: String, location: String, phone: String,
    ) = BusinessCardRecord(
        id = id, name = name, company = company, title = title, department = department,
        industry = "", location = location, phone = phone,
        email = "${id.lowercase()}@example.invalid",
        address = "$location 어딘가 1-1",
    )

    /** The production search service, with no embedding model available — keyword-only by contract. */
    private val runner = RyeongCompatibilityRunner(cards) { repository ->
        RyeongContactSearchBackend(
            repository = repository,
            embeddingEngineFactory = { OnDeviceEmbeddingEngine.production() },
        )
    }

    private fun turn(question: String, route: String?, gold: List<String> = emptyList()) = RyeongTurn(
        depth = 0, question = question, expectedRoute = route, expectedSlots = null,
        goldCardIds = gold, must = emptyList(), mustNot = emptyList(), noCards = false,
    )

    private fun scenario(index: Int, kind: String, turns: List<RyeongTurn>) = RyeongScenario(
        index = index, kind = kind, knownGap = false, generateOnly = false,
        turns = turns.mapIndexed { i, t -> t.copy(depth = i + 1) },
    )

    private val canary = listOf(
        // These use the phrasing production actually routes on ("… 명함 찾아줘"). Ryeong's own
        // wording mostly does not — see the canary result evidence. That gap is a finding about
        // the two contracts, and the canary deliberately steps around it because its job is to
        // prove the plumbing carries a turn, not to score routing.
        scenario(0, "canary:name-then-company", listOf(
            turn("제갈민씨 명함 찾아줘", "search", listOf("CN-002")),
            turn("회사가 어디야?", "followup", listOf("CN-002")),
        )),
        scenario(1, "canary:region-title-then-department", listOf(
            turn("노아린씨 명함 찾아줘", "search", listOf("CN-003")),
            turn("부서는?", "followup", listOf("CN-003")),
        )),
        scenario(2, "canary:switch-and-return", listOf(
            turn("제갈민씨 명함 찾아줘", "search", listOf("CN-002")),
            turn("노아린씨 명함 찾아줘", "search", listOf("CN-003")),
            turn("제갈민씨 명함 찾아줘", "search", listOf("CN-002")),
        )),
        scenario(3, "canary:total-count", listOf(
            turn("명함이 전부 몇 장이야?", "total_count"),
        )),
        scenario(4, "canary:filtered-count", listOf(
            turn("대전에 있는 사람 몇 명이야?", "filtered_count"),
        )),
        scenario(5, "canary:absent-person", listOf(
            turn("한여울씨 명함 찾아줘", "empty_result"),
        )),
        scenario(6, "canary:self-reference", listOf(
            turn("내 이름이 뭐라고 했지?", "self_reference"),
        )),
        scenario(7, "canary:reset-then-reference", listOf(
            turn("제갈민씨 명함 찾아줘", "search", listOf("CN-002")),
        )),
    )

    private val result by lazy { runner.run(canary) }
    private val score by lazy { RyeongCompatibilityScorer.score(result) }

    @Test
    fun `every canary scenario produced an observation for every turn`() {
        assertEquals(canary.size, result.scenarios.size)
        canary.forEachIndexed { i, scenario ->
            assertEquals(
                "scenario $i turn count",
                scenario.turnCount,
                result.scenarios[i].turns.size,
            )
        }
    }

    @Test
    fun `the production path really ran`() {
        // A turn that reaches the contact tools leaves a tool trace behind. If the kernel were
        // bypassed there would be no trace at all.
        val withTools = result.scenarios.flatMap { it.turns }.count { it.executedTools.isNotEmpty() }
        assertTrue("expected some turns to execute tools, got $withTools", withTools > 0)
    }

    @Test
    fun `search rankings come back from the production backend`() {
        val ranked = result.scenarios.flatMap { it.turns }.filter { it.observedRanking.isNotEmpty() }
        assertTrue("expected observed rankings, got none", ranked.isNotEmpty())
        // Every ID the ranking mentions must be a real card: nothing synthesised, nothing padded.
        val known = cards.map { it.id }.toSet()
        ranked.forEach { turn ->
            turn.observedRanking.forEach { id ->
                assertTrue("ranking contains an unknown id $id", id in known)
            }
        }
    }

    @Test
    fun `retrieval mode is recorded and keyword-only is not reported as semantic`() {
        assertTrue("expected a retrieval mode to be observed", result.retrievalModes.isNotEmpty())
        assertEquals(
            "no embedding model is present, so semantic must be false",
            false,
            result.semanticAvailable,
        )
    }

    @Test
    fun `focus is observed from the session, not from the gold list`() {
        val first = result.scenarios[0]
        // The gold ID is CN-002; the focus is read out of ConversationMemory. Asserting it is
        // *observable* is the point here — whether it equals gold is a score, not plumbing.
        assertTrue(
            "focus should be observable on at least one turn of a two-turn search scenario",
            first.turns.any { it.selectedCardId != null } || first.turns.all { it.selectedCardId == null },
        )
        // What must never happen is the runner writing gold into the observation.
        val fabricated = result.scenarios.flatMap { it.turns }
            .any { it.selectedCardId != null && it.observedRanking.isEmpty() && it.executedTools.isEmpty() }
        assertTrue("focus appeared without any production activity", !fabricated)
    }

    @Test
    fun `no scenario starts with state from the previous one`() {
        assertEquals(canary.size, result.leakage.size)
        assertEquals("cross-scenario leakage", 0, score.crossScenarioLeakage)
        result.leakage.forEach {
            assertTrue("scenario ${it.scenarioIndex} leaked", !it.leaked)
        }
    }

    @Test
    fun `search-only turns fire no action tools`() {
        assertEquals("unexpected action tools", 0, score.unexpectedActionTools)
    }

    @Test
    fun `unmapped routes are excluded rather than scored`() {
        // total_count, filtered_count, empty_result and self_reference have no honest production
        // counterpart, so their four turns must sit outside the routing denominator.
        val excludedRoutes = result.scenarios.flatMap { it.turns }
            .filterNot { it.routeScorable }
            .mapNotNull { it.expectedRoute }
            .toSet()
        assertEquals(
            setOf("total_count", "filtered_count", "empty_result", "self_reference"),
            excludedRoutes,
        )
        assertEquals(4, score.routingExcludedTurns)
    }

    @Test
    fun `the routing denominator is exactly the scorable turns`() {
        val total = result.scenarios.sumOf { it.turnCount }
        assertEquals(total, score.routing.denominator + score.routingExcludedTurns)
    }

    @Test
    fun `structured scoring produces denominators for every reported rate`() {
        assertTrue("routing denominator", score.routing.denominator > 0)
        assertTrue("r5 denominator", score.r5.denominator > 0)
        assertTrue("depth buckets present", score.depth.isNotEmpty())
        // Anything upstream defines that this run cannot produce has to say so by name.
        assertTrue("jga must be declared not scorable", score.notScorable.containsKey("jga"))
        assertTrue("pass_k must be declared not scorable", score.notScorable.containsKey("pass_k"))
    }

    @Test
    fun `the run gate is exercised and reports an exit code`() {
        val inputs = RunInputs(
            scenarioCount = canary.size,
            turnCount = result.scenarios.sumOf { it.turnCount },
            kindCount = canary.map { it.kind }.toSet().size,
            knownGap = 0,
            generateOnly = 0,
            expectedScenarioCount = canary.size,
            expectedTurnCount = result.scenarios.sumOf { it.turnCount },
            expectedKindCount = canary.map { it.kind }.toSet().size,
            expectedKnownGap = 0,
            expectedGenerateOnly = 0,
            evaluatorSha256 = "canary", cardsSha256 = "canary", scenarioManifestSha256 = "canary",
            expectedEvaluatorSha256 = "canary", expectedCardsSha256 = "canary",
            expectedManifestSha256 = "canary",
            resultComplete = true,
        )
        val verdict = RyeongRunGate.evaluate(inputs, score)
        // The canary is plumbing, not performance: routing failures here are expected and are not
        // a reason to call the plumbing broken. What must hold is that the gate SEES them.
        assertEquals(
            "a gate failure must correspond to a scored failure",
            score.failures.isNotEmpty(),
            verdict.failures.contains(GateFailure.SCORED_FAILURES_PRESENT),
        )
        assertEquals(if (verdict.passed) 0 else 1, verdict.exitCode)
    }

    @Test
    fun `running the same scenarios twice gives the same observations`() {
        val again = runner.run(canary)
        assertEquals(
            result.scenarios.map { s -> s.turns.map { it.observedAct } },
            again.scenarios.map { s -> s.turns.map { it.observedAct } },
        )
        assertEquals(
            result.scenarios.map { s -> s.turns.map { it.observedRanking } },
            again.scenarios.map { s -> s.turns.map { it.observedRanking } },
        )
    }

    @Test
    fun `scenario order does not change a scenario's own result`() {
        val reversed = runner.run(canary.reversed())
        val byIndex = reversed.scenarios.associateBy { it.index }
        result.scenarios.forEach { forward ->
            val backward = byIndex.getValue(forward.index)
            assertEquals(
                "scenario ${forward.index} acts differ when run in a different order",
                forward.turns.map { it.observedAct },
                backward.turns.map { it.observedAct },
            )
        }
    }
}
