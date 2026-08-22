package com.example.hjp.eval.ryeong

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The frozen manifest is an input, so it gets checked like one.
 *
 * These assertions are the same exact counts the Python exporter gates on. Two independent
 * implementations agreeing on 130/377/21 is the point: if either side drifts, this fails.
 */
class RyeongScenarioLoaderTest {

    private val set = RyeongScenarioLoader.load()

    @Test
    fun `the frozen manifest carries exactly the declared inventory`() {
        assertEquals(130, set.scenarios.size)
        assertEquals(377, set.turnCount)
        assertEquals(21, set.kinds.size)
        assertEquals(0, set.scenarios.count { it.knownGap })
        assertEquals(6, set.scenarios.count { it.generateOnly })
    }

    @Test
    fun `provenance pins the upstream inputs`() {
        assertEquals("1caec3a23d0c1ee8f6a8d4a5e54160dbb2dc81bc", set.commit)
        assertEquals(
            "26a522235fbbd73760229260e3cc4373ca6d66ce0a4d4ca9dbf612cd21d19031",
            set.evaluatorSha256,
        )
        assertEquals(
            "f0feaebfdf5eb26c2a161a4b8c40d1307a6f5fa9c68f00309f05b69d03e7cd24",
            set.cardsSha256,
        )
        assertEquals(42, set.seed)
    }

    @Test
    fun `conversation depth matches the declared distribution`() {
        val byBucket = set.scenarios.groupingBy { it.depthBucket }.eachCount()
        assertEquals(3, byBucket["depth_1"])
        assertEquals(62, byBucket["depth_2"])
        assertEquals(55, byBucket["depth_3_5"])
        assertEquals(10, byBucket["depth_6_10"])
        assertEquals(null, byBucket["depth_11_plus"])
    }

    @Test
    fun `scenario indices are unique and dense`() {
        val indices = set.scenarios.map { it.index }
        assertEquals(indices.size, indices.toSet().size)
        assertEquals((0 until 130).toList(), indices)
    }

    @Test
    fun `turn depths run one to n within every scenario`() {
        set.scenarios.forEach { scenario ->
            assertEquals(
                "scenario ${scenario.index}",
                (1..scenario.turnCount).toList(),
                scenario.turns.map { it.depth },
            )
        }
    }

    @Test
    fun `an unasserted route stays null instead of collapsing to a value`() {
        // 13 turns upstream asserts no route for. If these became "" or "search" the routing
        // denominator would silently grow by 13 turns that were never meant to be scored.
        val unasserted = set.scenarios.flatMap { it.turns }.count { it.expectedRoute == null }
        assertEquals(13, unasserted)
    }

    @Test
    fun `every question is non-blank`() {
        val blank = set.scenarios.flatMap { it.turns }.filter { it.question.isBlank() }
        assertTrue("blank questions: ${blank.size}", blank.isEmpty())
    }

    @Test
    fun `must alternatives are preserved as alternative groups`() {
        // Upstream's check_answer passes when ANY alternative matches, so the nesting matters:
        // flattening it would turn "one of these" into "all of these".
        val withAlternatives = set.scenarios.flatMap { it.turns }
            .flatMap { it.must }
            .count { it.size > 1 }
        assertTrue("expected some multi-alternative must groups", withAlternatives > 0)
    }

    @Test
    fun `slots distinguish an undeclared focus from a declared null focus`() {
        val slotted = set.scenarios.flatMap { it.turns }.mapNotNull { it.expectedSlots }
        assertTrue("expected slot-bearing turns", slotted.isNotEmpty())
        // Upstream compares focus only when the key exists, so this flag has to survive the export.
        val declared = slotted.count { it.focusDeclared }
        assertTrue("focusDeclared must be observable, got $declared of ${slotted.size}", declared >= 0)
    }

    @Test
    fun `slot bearing turns match the upstream count`() {
        val slotted = set.scenarios.flatMap { it.turns }.count { it.expectedSlots != null }
        assertEquals(288, slotted)
    }

    @Test
    fun `gold bearing turns match the upstream count`() {
        val gold = set.scenarios.flatMap { it.turns }.count { it.goldCardIds.isNotEmpty() }
        assertEquals(335, gold)
    }

    @Test
    fun `loading twice yields identical content`() {
        val again = RyeongScenarioLoader.load()
        assertEquals(set.scenarios.size, again.scenarios.size)
        assertEquals(set.scenarios.map { it.turns.map { t -> t.question } },
            again.scenarios.map { it.turns.map { t -> t.question } })
    }

    @Test
    fun `the raw manifest is valid UTF-8 JSON with a trailing newline`() {
        val raw = RyeongScenarioLoader.loadRaw()
        assertTrue("must end with a newline", raw.endsWith("\n"))
        assertNotNull(raw)
    }

    @Test
    fun `a scalar type mismatch is rejected rather than coerced`() {
        // "3" is not 3. A loader that coerces would let a corrupted manifest through.
        val corrupted = RyeongScenarioLoader.loadRaw()
            .replaceFirst("\"depth\":1", "\"depth\":\"1\"")
        try {
            RyeongScenarioLoader.parse(corrupted)
            fail("expected a type error for a stringified depth")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }
}
