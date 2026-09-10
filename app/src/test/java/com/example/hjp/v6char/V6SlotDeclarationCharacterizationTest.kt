package com.example.hjp.v6char

import com.example.hjp.eval.ryeong2.J
import com.example.hjp.eval.v6.V6Contract
import com.example.hjp.eval.v6.V6DatasetLoader
import com.example.hjp.eval.v6.V6SlotState
import com.example.hjp.eval.v6.V6Slots
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `expected_slots` has four states, and a reader that reports two of them is reading a different
 * dataset from the one on disk.
 *
 * The four are not stylistic variants of each other:
 *
 *  - **absent** is a dataset that lost a key. Nothing about the turn is asserted *and* nothing about
 *    the dataset can be trusted, so it is a contract error, not a measurement.
 *  - **explicit null** is a scenario that deliberately asserts nothing. The turn may resolve a name,
 *    apply a title, select a focus — none of it enters a denominator, because there is no claim to
 *    compare it against. This is the state v5's Python reader lost, and losing it put 33 turns into
 *    a JGA denominator of 258 against an authoritative 225.
 *  - **empty object** is a scenario that asserts an empty slot set. A turn that resolves a name here
 *    is *wrong*, and it must be scored as wrong rather than excused as unasserted.
 *  - **populated** is the ordinary comparison.
 *
 * Every case comes from `tools/ryeong_official_v6/contracts/characterization_cases.json`, which the
 * Python side reads too, so the two implementations are answering the same questions.
 */
class V6SlotDeclarationCharacterizationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `every declared case lands where the contract says it lands`() {
        val expectations = V6CharacterizationCases.expectations()
        assertTrue("the case file declares no turn cases", expectations.isNotEmpty())

        val verdicts = expectations.map { it to V6CharacterizationCases.judge(it.id) }
        writeVerdicts(verdicts)

        val problems = verdicts.mapNotNull { (expected, actual) ->
            val differences = buildList {
                if (actual.slotState != expected.slotState) {
                    add("slot_state expected=${expected.slotState} actual=${actual.slotState}")
                }
                if (actual.jgaState != expected.jgaState) {
                    add("jga_state expected=${expected.jgaState} actual=${actual.jgaState}")
                }
                if (actual.jgaPass != expected.jgaPass) {
                    add("jga_pass expected=${expected.jgaPass} actual=${actual.jgaPass}")
                }
                if (actual.focusState != expected.focusState) {
                    add("focus_state expected=${expected.focusState} actual=${actual.focusState}")
                }
                if (actual.focusPass != expected.focusPass) {
                    add("focus_pass expected=${expected.focusPass} actual=${actual.focusPass}")
                }
            }
            if (differences.isEmpty()) null else "${expected.id}: ${differences.joinToString("; ")}"
        }

        assertEquals(
            "the slot declaration contract is not honoured:\n" + problems.joinToString("\n"),
            emptyList<String>(),
            problems,
        )
    }

    @Test
    fun `an absent key and an explicit null are different states`() {
        val (withKey, _) = V6CharacterizationCases.materialise("null_slots_no_observation")
        val (withoutKey, _) = V6CharacterizationCases.materialise("missing_slots_key")

        assertTrue("the case file's null turn must carry the key",
            withKey.containsKey("expected_slots"))
        assertTrue("the case file's missing turn must not carry the key",
            !withoutKey.containsKey("expected_slots"))

        val nullState = V6Slots.read(withKey).state
        val missingState = V6Slots.read(withoutKey).state

        assertEquals("an explicit null must read as NULL", V6SlotState.NULL, nullState)
        assertEquals("an absent key must read as MISSING", V6SlotState.MISSING, missingState)
    }

    @Test
    fun `an empty object and an explicit null are different states`() {
        val (nullTurn, _) = V6CharacterizationCases.materialise("null_slots_no_observation")
        val (emptyTurn, _) = V6CharacterizationCases.materialise("empty_slots_no_observation")

        assertEquals(V6SlotState.NULL, V6Slots.read(nullTurn).state)
        assertEquals(V6SlotState.EMPTY, V6Slots.read(emptyTurn).state)
    }

    @Test
    fun `the frozen dataset's own slot state census is what the case file declares`() {
        val declared = V6Contract.cases()["real_data_cases"]!!
        val expected = json.parseToJsonElement(declared.toString())
        val case = expected.let { element ->
            (element as kotlinx.serialization.json.JsonArray)
                .map { it.jsonObject }
                .first { (it["id"] as kotlinx.serialization.json.JsonPrimitive).content ==
                    "k5_slot_declaration_census" }
        }
        val want = (case["expect"] as JsonObject)
        val dataset = V6DatasetLoader.load()
        val census = dataset.slotStateCensus()
        V6Contract.SLOT_STATES.forEach { state ->
            val declaredCount =
                (want[state] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
            assertEquals("slot state $state", declaredCount, census[state])
        }
        assertEquals(
            "the four states must account for every turn",
            (want["total"] as kotlinx.serialization.json.JsonPrimitive).content.toInt(),
            census.values.sum(),
        )
    }

    @Test
    fun `the v6 dataset reader agrees with the frozen loader about everything except slot state`() {
        val dataset = V6DatasetLoader.load()
        val drift = V6DatasetLoader.crossCheck(dataset)
        assertEquals(
            "the v6 dataset reader disagrees with the frozen v2 loader:\n" + drift.joinToString("\n"),
            emptyList<String>(),
            drift,
        )
    }

    private fun writeVerdicts(
        verdicts: List<Pair<V6CharacterizationCases.Expectation, V6CharacterizationCases.TurnVerdict>>,
    ) {
        val file = File(V6CharacterizationCases.evidenceDir(), "KOTLIN_CASE_VERDICTS.json")
        file.writeText(
            J.obj(
                "schema" to J.q("ryeong_v6_case_verdicts/v1"),
                "implementation" to J.q("kotlin"),
                "cases_sha256" to J.q(V6Contract.casesSha256()),
                "contract_sha256" to J.q(V6Contract.sha256()),
                "case_count" to J.num(verdicts.size),
                "verdicts" to J.arr(
                    verdicts.map { (expected, actual) ->
                        J.obj(
                            "id" to J.q(actual.id),
                            "slot_state" to J.q(actual.slotState),
                            "jga_state" to J.q(actual.jgaState),
                            "jga_pass" to (actual.jgaPass?.let { J.bool(it) } ?: "null"),
                            "focus_state" to J.q(actual.focusState),
                            "focus_pass" to (actual.focusPass?.let { J.bool(it) } ?: "null"),
                            "matches_expectation" to J.bool(
                                actual.slotState == expected.slotState &&
                                    actual.jgaState == expected.jgaState &&
                                    actual.jgaPass == expected.jgaPass &&
                                    actual.focusState == expected.focusState &&
                                    actual.focusPass == expected.focusPass,
                            ),
                        )
                    },
                ),
            ) + "\n",
            Charsets.UTF_8,
        )
    }
}
