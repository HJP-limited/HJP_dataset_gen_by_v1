package com.example.hjp.v8char

import com.example.hjp.v8char.V8CharacterizationFixture.CARDINALITY_CONTRACT
import com.example.hjp.v8char.V8CharacterizationFixture.FROZEN_V7_CASE_ID
import com.example.hjp.v8char.V8CharacterizationFixture.KOTLIN_READER
import com.example.hjp.v8char.V8CharacterizationFixture.PYTHON_CARDINALITY_REPORT
import com.example.hjp.v8char.V8CharacterizationFixture.PYTHON_READER
import com.example.hjp.v8char.V8CharacterizationFixture.V6_TEMPLATE
import com.example.hjp.v8char.V8CharacterizationFixture.V7_CASE_TABLE
import com.example.hjp.v8char.V8CharacterizationFixture.V7_TEMPLATE
import com.example.hjp.v8char.V8CharacterizationFixture.V8_TEMPLATE
import com.example.hjp.v8char.V8CharacterizationFixture.activePathPolicy
import com.example.hjp.v8char.V8CharacterizationFixture.distinctTargets
import com.example.hjp.v8char.V8CharacterizationFixture.intOf
import com.example.hjp.v8char.V8CharacterizationFixture.json
import com.example.hjp.v8char.V8CharacterizationFixture.objectOrNull
import com.example.hjp.v8char.V8CharacterizationFixture.occurrencePointers
import com.example.hjp.v8char.V8CharacterizationFixture.pointerToTarget
import com.example.hjp.v8char.V8CharacterizationFixture.present
import com.example.hjp.v8char.V8CharacterizationFixture.requireObject
import com.example.hjp.v8char.V8CharacterizationFixture.scan
import com.example.hjp.v8char.V8CharacterizationFixture.sha256
import com.example.hjp.v8char.V8CharacterizationFixture.stringOf
import com.example.hjp.v8char.V8CharacterizationFixture.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v7 oracle's unit error, as a test — and as two units rather than one number.
 *
 * v7 found the defect and then checked it against an expectation that counted the wrong thing. The
 * v6 Phase B template is wrong about three targets; those three targets occur in five operational
 * positions. `violation_count: 3` in the v7 case table is the second sentence written in the first
 * sentence's field, and the accompanying prose — "the three placeholder hints" — is the same
 * conflation in words.
 *
 * The v7 *scanner* was never confused: it published `violation_count: 5` and
 * `distinct_target_count: 3` in the same record. Only the frozen expectation was wrong, and v7's own
 * rule — a characterization expectation is frozen at RED and never edited afterwards — is why it was
 * left failing rather than quietly corrected. That rule is right. An oracle you may adjust once you
 * have seen the answer has stopped being an oracle.
 *
 * So this suite does not recount. It makes the two units impossible to confuse again: each gets its
 * own contract field, its own name, its own policy and its own case here. And it checks the
 * contract's *shape* as hard as its values, because a contract that reports 5 but cannot say which
 * pointer maps to which target has got the right answer by luck.
 *
 * The scan this file performs is its own, written from the policy document rather than borrowed from
 * either reader, so that the characterization can disagree with both. Two implementations that agree
 * is the point; one implementation checked against itself is not.
 *
 * Case ids refer to `tools/ryeong_official_v8/contracts/characterization_cases_v8.json`, frozen at
 * RED and unmodified since.
 */
class V8PhaseBCardinalityCharacterizationTest {

    private fun v6Occurrences() = scan(requireObject(V6_TEMPLATE), activePathPolicy(), "v6")

    private fun contract(): JsonObject? = objectOrNull(CARDINALITY_CONTRACT)

    private fun pythonReport(): JsonObject? = objectOrNull(PYTHON_CARDINALITY_REPORT)

    // ==============================================================================================
    // C01 / C04 — what v7 froze, and the arithmetic that shows it was the wrong unit
    // ==============================================================================================

    @Test
    fun `C01 the frozen v7 case recorded three where it should have recorded positions`() {
        val table = requireObject(V7_CASE_TABLE)
        val cases = table["defect_b_real_templates"] as JsonArray
        val frozen = cases.map { it.jsonObject }.single { stringOf(it["id"]) == FROZEN_V7_CASE_ID }
        val expect = frozen["expect"]!!.jsonObject

        assertEquals(
            "the frozen v7 expectation is no longer 3; the historical record has moved",
            3, intOf(expect["violation_count"]),
        )
        assertEquals(
            "the frozen v7 case no longer lists three violations",
            3, (expect["violations"] as JsonArray).size,
        )
        val names = (expect["violations"] as JsonArray).map { stringOf(it.jsonObject["names"]) }
        assertEquals(
            "the frozen entries are one per target, which is what made the count a target count",
            names.size, names.distinct().size,
        )
    }

    @Test
    fun `C04 the frozen expectation equals the target count and not the occurrence count`() {
        val occurrences = v6Occurrences()
        val frozenExpectation = 3

        assertEquals(
            "the frozen 3 no longer equals the distinct-target count, so the diagnosis has changed",
            distinctTargets(occurrences).size, frozenExpectation,
        )
        assertTrue(
            "the frozen 3 now equals the occurrence count, which would mean there was never a " +
                "conflation to correct: ${occurrences.size}",
            occurrences.size != frozenExpectation,
        )
    }

    // ==============================================================================================
    // C02 / C03 / C10 / C11 — the real numbers, from the real template
    // ==============================================================================================

    @Test
    fun `C02 the real v6 template has five operational occurrence pointers`() {
        val occurrences = v6Occurrences()
        assertEquals(
            "the v6 template no longer yields five operational occurrences: " +
                occurrencePointers(occurrences),
            5, occurrences.size,
        )
        assertEquals(
            "the occurrence pointers are not the five positions v7's scanner reported",
            listOf(
                "/preflight/official_v5_namespace_untouched",
                "/host_analysis/score_verdict",
                "/host_analysis/recomputation_agrees",
                "/contact_dependency/host_scorer_findings",
                "/contact_dependency/host_recomputation_findings",
            ).sorted(),
            occurrencePointers(occurrences).sorted(),
        )
    }

    @Test
    fun `C03 the real v6 template has three distinct invalid targets`() {
        assertEquals(
            "the v6 template no longer yields three distinct targets",
            listOf("DEVICE_RECOMPUTATION.json", "DEVICE_SCORE.json", "v5"),
            distinctTargets(v6Occurrences()),
        )
    }

    @Test
    fun `C10 a target named from two pointers contributes two occurrences and one target`() {
        val occurrences = v6Occurrences()
        val byTarget = occurrences.groupBy { it.normalizedTarget }

        assertEquals(
            "DEVICE_SCORE.json is no longer hinted at from two positions",
            listOf(
                "/contact_dependency/host_scorer_findings",
                "/host_analysis/score_verdict",
            ),
            byTarget["DEVICE_SCORE.json"].orEmpty().map { it.pointer }.sorted(),
        )
        assertEquals(
            "DEVICE_RECOMPUTATION.json is no longer hinted at from two positions",
            listOf(
                "/contact_dependency/host_recomputation_findings",
                "/host_analysis/recomputation_agrees",
            ),
            byTarget["DEVICE_RECOMPUTATION.json"].orEmpty().map { it.pointer }.sorted(),
        )
        assertEquals(
            "the v5 namespace is no longer named from exactly one position",
            listOf("/preflight/official_v5_namespace_untouched"),
            byTarget["v5"].orEmpty().map { it.pointer }.sorted(),
        )
        assertEquals(
            "2 + 2 + 1 is the whole of the occurrence count; a repeated target must not collapse",
            5, byTarget.values.sumOf { it.size },
        )
        assertEquals("three groups is the whole of the target count", 3, byTarget.size)
    }

    @Test
    fun `C11 the distinct target set collapses by target and by nothing else`() {
        val occurrences = v6Occurrences()
        val targets = distinctTargets(occurrences)

        assertEquals("the target list is not a set", targets.size, targets.distinct().size)
        assertTrue(
            "every distinct target must come from an occurrence; the set is derived, not declared",
            targets.all { target -> occurrences.any { it.normalizedTarget == target } },
        )
        assertTrue(
            "the target count must be smaller than the occurrence count for this template, or the " +
                "two units would be indistinguishable here and this case would prove nothing",
            targets.size < occurrences.size,
        )
    }

    // ==============================================================================================
    // C13 — the counts are traversal-derived, not constants
    // ==============================================================================================

    @Test
    fun `C13 the scan derives its counts by walking the document`() {
        val policy = activePathPolicy()

        val empty = json.parseToJsonElement("""{"schema":"x"}""") as JsonObject
        assertEquals("an empty document must yield no occurrences",
            0, scan(empty, policy, "v6").size)
        assertEquals("an empty document must yield no targets",
            0, distinctTargets(scan(empty, policy, "v6")).size)

        val one = json.parseToJsonElement(
            """{"a":"<from DEVICE_SCORE.json>"}""",
        ) as JsonObject
        assertEquals("one offending position must yield one occurrence",
            1, scan(one, policy, "v6").size)
        assertEquals("one offending target must yield one target",
            1, distinctTargets(scan(one, policy, "v6")).size)

        val twoPointersOneTarget = json.parseToJsonElement(
            """{"a":"<from DEVICE_SCORE.json>","b":"<also from DEVICE_SCORE.json>"}""",
        ) as JsonObject
        val occurrences = scan(twoPointersOneTarget, policy, "v6")
        assertEquals(
            "two positions naming one target must yield two occurrences — this is the case a " +
                "scanner that returns a constant 5, or that deduplicates by target, cannot pass",
            2, occurrences.size,
        )
        assertEquals("two positions naming one target must yield one target",
            1, distinctTargets(occurrences).size)
    }

    // ==============================================================================================
    // C12 / C14 — the correction must not invent findings
    // ==============================================================================================

    @Test
    fun `C12 the v7 template is clean under both units`() {
        val occurrences = scan(requireObject(V7_TEMPLATE), activePathPolicy(), "v7")
        assertEquals("the v7 template gained an operational occurrence: $occurrences",
            0, occurrences.size)
        assertEquals("the v7 template gained a distinct invalid target",
            0, distinctTargets(occurrences).size)
    }

    @Test
    fun `C14 the v8 template is clean under both units`() {
        assertTrue("the v8 Phase B template is not in the tree", present(V8_TEMPLATE))
        val occurrences = scan(requireObject(V8_TEMPLATE), activePathPolicy(), "v8")
        assertEquals("the v8 template names an earlier version operationally: $occurrences",
            0, occurrences.size)
        assertEquals("the v8 template has a distinct invalid target",
            0, distinctTargets(occurrences).size)
    }

    // ==============================================================================================
    // C05 … C09, C15 … C18 — the contract's shape
    // ==============================================================================================

    @Test
    fun `C05 the cardinality contract exists and gives each unit its own field`() {
        val document = contract()
        assertNotNull("the v8 cardinality contract is not in the tree: $CARDINALITY_CONTRACT",
            document)
        val required = listOf(
            "operational_occurrence_count", "distinct_invalid_target_count",
            "operational_occurrence_pointers", "distinct_invalid_targets", "pointer_to_target",
            "pointer_uniqueness_policy", "target_normalization_policy", "ordering_policy",
            "duplicate_pointer_policy", "duplicate_target_policy", "position_classification_policy",
            "historical_v7_misclassification", "source_template_sha", "python_reader_sha",
            "kotlin_reader_sha",
        )
        required.forEach { field ->
            assertTrue("the contract does not carry $field", document!!.containsKey(field))
        }
        assertEquals("the contract's occurrence count is not the five the template really has",
            5, intOf(document!!["operational_occurrence_count"]))
        assertEquals("the contract's distinct target count is not three",
            3, intOf(document["distinct_invalid_target_count"]))
        assertTrue(
            "the two counts are the same field or the same number; separating them is the whole " +
                "correction",
            intOf(document["operational_occurrence_count"]) !=
                intOf(document["distinct_invalid_target_count"]),
        )
    }

    @Test
    fun `C06 the pointer-to-target mapping is present, total and onto`() {
        val document = contract()
        assertNotNull("the v8 cardinality contract is not in the tree", document)
        val mapping = document!!["pointer_to_target"] as? JsonObject
        assertNotNull("the contract carries no pointer_to_target mapping", mapping)

        val pointers = strings(document["operational_occurrence_pointers"])
        val targets = strings(document["distinct_invalid_targets"])
        assertEquals("the mapping does not cover every occurrence pointer",
            pointers.sorted(), mapping!!.keys.sorted())
        assertEquals("the mapping's range is not the declared distinct-target set",
            targets.sorted(), mapping.values.mapNotNull { stringOf(it) }.distinct().sorted())
        assertEquals("the mapping is not the size of the occurrence list", 5, mapping.size)

        val derived = pointerToTarget(v6Occurrences())
        assertEquals("the contract's mapping is not the one the template actually produces",
            derived, mapping.mapValues { stringOf(it.value) })
    }

    @Test
    fun `C07 the python reader and this kotlin derivation agree`() {
        val report = pythonReport()
        assertNotNull("the python cardinality report is not in the tree: $PYTHON_CARDINALITY_REPORT",
            report)
        val v6 = report!!["v6_template"] as? JsonObject
        assertNotNull("the python report carries no v6_template block", v6)

        val occurrences = v6Occurrences()
        assertEquals("python and kotlin disagree on the occurrence count",
            occurrences.size, intOf(v6!!["operational_occurrence_count"]))
        assertEquals("python and kotlin disagree on the distinct target count",
            distinctTargets(occurrences).size, intOf(v6["distinct_invalid_target_count"]))
        assertEquals("python and kotlin disagree on the pointer set",
            occurrencePointers(occurrences).sorted(),
            strings(v6["operational_occurrence_pointers"]).sorted())
        assertEquals("python and kotlin disagree on the target set",
            distinctTargets(occurrences), strings(v6["distinct_invalid_targets"]).sorted())
        assertEquals("python and kotlin disagree on the pointer-to-target mapping",
            pointerToTarget(occurrences),
            (v6["pointer_to_target"] as JsonObject).mapValues { stringOf(it.value) })
    }

    @Test
    fun `C08 the contract declares how the pointer list is ordered`() {
        val document = contract()
        assertNotNull("the v8 cardinality contract is not in the tree", document)
        val ordering = document!!["ordering_policy"] as? JsonObject
        assertNotNull("the contract declares no ordering policy", ordering)
        assertTrue(
            "the ordering policy names no rule; two readers cannot agree on a list without one",
            !stringOf(ordering!!["pointer_list_order"]).isNullOrBlank(),
        )
        assertTrue(
            "the contract does not say whether equality comparison is order-sensitive",
            ordering.containsKey("equality_comparison"),
        )
        assertEquals(
            "the contract's pointer list is not in the order the contract says it is in",
            occurrencePointers(v6Occurrences()),
            strings(document["operational_occurrence_pointers"]),
        )
    }

    @Test
    fun `C09 the contract declares a duplicate-pointer policy and obeys it`() {
        val document = contract()
        assertNotNull("the v8 cardinality contract is not in the tree", document)
        assertTrue("the contract declares no duplicate_pointer_policy",
            document!!.containsKey("duplicate_pointer_policy"))
        assertTrue("the contract declares no duplicate_target_policy",
            document.containsKey("duplicate_target_policy"))
        assertTrue("the contract declares no pointer_uniqueness_policy",
            document.containsKey("pointer_uniqueness_policy"))

        val pointers = strings(document["operational_occurrence_pointers"])
        assertEquals("the contract's pointer list repeats a pointer",
            pointers.size, pointers.distinct().size)
        val targets = strings(document["distinct_invalid_targets"])
        assertEquals("the contract's target list repeats a target",
            targets.size, targets.distinct().size)
    }

    @Test
    fun `C15 the contract declares what makes a position operational, per pointer`() {
        val document = contract()
        assertNotNull("the v8 cardinality contract is not in the tree", document)
        val classification = document!!["position_classification_policy"] as? JsonObject
        assertNotNull("the contract declares no position_classification_policy", classification)
        assertTrue(
            "the classification policy does not say it is fail-closed, which is the property that " +
                "stops an undeclared position from being silently exempt",
            stringOf(classification!!["default"])?.contains("OPERATIONAL") == true,
        )
        val detail = document["occurrence_detail"] as? JsonArray
        assertNotNull("the contract carries no per-occurrence detail", detail)
        assertEquals("the per-occurrence detail is not one row per occurrence", 5, detail!!.size)
        detail.map { it.jsonObject }.forEach { row ->
            listOf("json_pointer", "position_classification", "detected_token",
                "normalized_target", "violation_code", "source_value", "why_operational")
                .forEach { field ->
                    assertTrue(
                        "an occurrence row does not record $field: ${stringOf(row["json_pointer"])}",
                        row.containsKey(field) && !stringOf(row[field]).isNullOrBlank(),
                    )
                }
        }
    }

    @Test
    fun `C16 the contract carries the v7 misclassification it exists to prevent`() {
        val document = contract()
        assertNotNull("the v8 cardinality contract is not in the tree", document)
        val historical = document!!["historical_v7_misclassification"] as? JsonObject
        assertNotNull("the contract does not record the v7 misclassification", historical)
        assertEquals("the contract does not record the correct distinct-target count",
            3, intOf(historical!!["expected_distinct_invalid_target_count"]))
        assertEquals("the contract does not record the observed distinct-target count",
            3, intOf(historical["observed_distinct_invalid_target_count"]))
        assertEquals("the contract does not record the incorrect frozen occurrence expectation",
            3, intOf(historical["incorrect_frozen_operational_occurrence_expectation"]))
        assertEquals("the contract does not record the observed occurrence count",
            5, intOf(historical["observed_operational_occurrence_count"]))
        assertEquals("the contract does not name the finding code",
            "V7_CHARACTERIZATION_ORACLE_UNIT_CONFLATION", stringOf(historical["finding_code"]))
        assertEquals("the contract does not name the reason code",
            "DISTINCT_TARGET_COUNT_USED_AS_OCCURRENCE_COUNT", stringOf(historical["reason_code"]))
    }

    @Test
    fun `C17 the contract declares how a detected token becomes a normalised target`() {
        val document = contract()
        assertNotNull("the v8 cardinality contract is not in the tree", document)
        val normalization = document!!["target_normalization_policy"] as? JsonObject
        assertNotNull("the contract declares no target_normalization_policy", normalization)
        assertTrue(
            "the normalisation policy does not say what a version token normalises to, so `v5` " +
                "and `ryeong_device_eval_v5_official` would look like a disagreement about the " +
                "answer rather than about the vocabulary",
            normalization!!.containsKey("version_token"),
        )
        assertTrue(
            "the policy does not record that the v7 case named the same finding differently",
            normalization.containsKey("historical_v7_naming_difference"),
        )
        val mapping = document["pointer_to_target"] as JsonObject
        assertEquals(
            "the v5 finding does not normalise to the token the scanner reports",
            "v5", stringOf(mapping["/preflight/official_v5_namespace_untouched"]),
        )
    }

    @Test
    fun `C18 the contract pins the digests of the template and of both readers`() {
        val document = contract()
        assertNotNull("the v8 cardinality contract is not in the tree", document)
        assertEquals(
            "the contract's pinned v6 template digest is not the template in the tree",
            sha256(V6_TEMPLATE), stringOf(document!!["source_template_sha"]),
        )
        assertEquals(
            "the contract's pinned python reader digest is not the reader in the tree",
            sha256(PYTHON_READER), stringOf(document["python_reader_sha"]),
        )
        assertEquals(
            "the contract's pinned kotlin reader digest is not the reader in the tree",
            sha256(KOTLIN_READER), stringOf(document["kotlin_reader_sha"]),
        )
    }
}
