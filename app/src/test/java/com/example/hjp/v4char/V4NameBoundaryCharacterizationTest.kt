package com.example.hjp.v4char

import com.example.hjp.MultiturnScenarioHarness
import com.hjp.agent.contract.TurnOutcomeType
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.3 — where a person's name ends, and where a particle begins.
 *
 * A recipient extractor that strips Korean particles from the end of a token cannot tell 하도을 (a
 * person) from 하도 + 을 (a different person plus an object marker). Getting that wrong sends mail to
 * the wrong human being, so the contract is exact-first: the longest span that is *actually a name in
 * this store* wins, and a particle-stripped form is only ever a secondary candidate.
 *
 * The names here are chosen for shape and nothing else — a name ending in 을, a name ending in 좀, a
 * two-syllable surname, a rare surname, a Latin name, a spaced name, a long name, and names that
 * collide with an ordinary noun or a company name. No test knows a scenario or a case id.
 */
class V4NameBoundaryCharacterizationTest {

    private fun harness(vararg cards: BusinessCardRecord) =
        MultiturnScenarioHarness(cards = cards.toList())

    /** Every name shape must be findable by its own name, in several request forms. */
    @Test
    fun `every name shape is found by its own name`() = runBlocking {
        V4Cards.NAME_SHAPES.forEach { card ->
            listOf(
                "${card.name} 명함 찾아줘.",
                "${card.name} 연락처 좀 띄워줘.",
                "${card.name} 명함 보여주세요.",
            ).forEach { text ->
                val h = harness(*V4Cards.NAME_SHAPES.toTypedArray())
                val turn = h.turn(text)
                val reached = turn.searchRankings.flatten() + listOfNotNull(turn.memory.selectedContact?.cardId)
                assertTrue(
                    "'$text' never reached ${card.name} (${card.id}); reached=$reached",
                    card.id in reached,
                )
                h.close()
            }
        }
    }

    /**
     * The exact-first rule, stated where it bites: 하도을 and 하도 are both real people in this store.
     * A request naming 하도을 must not resolve onto 하도.
     */
    @Test
    fun `a name that ends in a particle-like syllable is not stripped onto a different person`() =
        runBlocking {
            val full = V4Cards.ENDS_LIKE_PARTICLE
            val stripped = V4Cards.STRIPPED_TWIN
            listOf(
                "${full.name} 명함 찾아줘.",
                "${full.name}에게 ${V4Phrasing.MAIL_REQUESTS.first()}",
                "${full.name} 연락처 좀 띄워줘.",
            ).forEach { text ->
                val h = harness(full, stripped)
                val turn = h.turn(text)
                assertTrue(
                    "'$text' selected ${stripped.name} instead of ${full.name}",
                    turn.memory.selectedContact?.cardId != stripped.id,
                )
                V4Assert.assertNeverContacted(h, stripped.email, stripped.mobile)
                h.close()
            }
        }

    /** The same trap with 좀, which is also a politeness particle. */
    @Test
    fun `a name ending in the politeness particle syllable still resolves to that person`() =
        runBlocking {
            val card = V4Cards.ENDS_LIKE_JOM
            val h = harness(card, V4Cards.ANCHOR)
            val turn = h.turn("${card.name} 명함 찾아줘.")
            assertEquals(card.id, turn.memory.selectedContact?.cardId)
            h.close()
        }

    /**
     * A common-noun name and a company-like name must not be swallowed by the domain vocabulary.
     *
     * 정보라 contains 정보; 한빛나 shares a prefix with 한빛물산. Both are people.
     */
    @Test
    fun `names that collide with domain nouns and company names still resolve`() = runBlocking {
        listOf(V4Cards.COMMON_NOUN_NAME, V4Cards.COMPANY_LIKE_NAME).forEach { card ->
            val h = harness(*V4Cards.NAME_SHAPES.toTypedArray())
            val turn = h.turn("${card.name} 명함 찾아줘.")
            val reached = turn.searchRankings.flatten() + listOfNotNull(turn.memory.selectedContact?.cardId)
            assertTrue("${card.name} was not reached; reached=$reached", card.id in reached)
            h.close()
        }
    }

    /** A job title, a department or a company name on its own is not a person. */
    @Test
    fun `a title department or company alone is not treated as a recipient`() = runBlocking {
        listOf(
            "품질팀장에게 ${V4Phrasing.MAIL_REQUESTS.first()}",
            "전략기획 부서에 ${V4Phrasing.MAIL_REQUESTS.first()}",
            "한빛물산에 ${V4Phrasing.MAIL_REQUESTS.first()}",
        ).forEach { text ->
            val h = harness(*V4Cards.NAME_SHAPES.toTypedArray())
            val turn = h.turn(text)
            assertTrue(
                "'$text' opened a compose screen from a non-person target",
                turn.newComposeDrafts.isEmpty(),
            )
            h.close()
        }
    }

    /** Namesakes are asked about, never picked. */
    @Test
    fun `two people with one name are disambiguated rather than chosen`() = runBlocking {
        val h = harness(V4Cards.TWIN_A, V4Cards.TWIN_B)
        val turn = h.turn("${V4Cards.TWIN_A.name}에게 ${V4Phrasing.MAIL_REQUESTS.first()}")
        assertEquals(0, turn.newComposeDrafts.size)
        assertEquals(TurnOutcomeType.CLARIFICATION_REQUIRED, turn.outcomeType)
        h.close()
    }

    /** An exact match and a partial match in the same store: the exact one wins. */
    @Test
    fun `an exact name match wins over a partial one`() = runBlocking {
        val exact = V4Cards.STRIPPED_TWIN          // 하도
        val partial = V4Cards.ENDS_LIKE_PARTICLE   // 하도을 — contains 하도
        val h = harness(exact, partial)
        val turn = h.turn("${exact.name} 명함 찾아줘.")
        val ranking = turn.searchRankings.flatten()
        assertTrue(
            "an exact match must rank first; ranking=$ranking",
            ranking.isEmpty() || ranking.first() == exact.id,
        )
        h.close()
    }
}
