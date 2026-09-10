package com.example.hjp.v5

import com.example.hjp.eval.ryeong2.EvidenceRoot
import com.example.hjp.eval.ryeong2.J
import com.example.hjp.eval.v4.ToolSemanticCompletion
import com.example.hjp.eval.v5.RyeongV5Findings
import com.example.hjp.eval.v5.V5ActionProvenance
import com.example.hjp.eval.v5.V5Verification
import com.example.hjp.eval.v5.V5ContactDependencyContract
import com.example.hjp.eval.v5.V5ContactStore
import com.example.hjp.eval.v5.V5TurnObservation
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both implementations, the same questions.
 *
 * The Kotlin evaluator and the Python device scorer were written independently on purpose — one
 * scores the host run, the other scores the device run, and a single implementation used twice
 * cannot catch its own mistake. Independence is only worth having if both are asked the same
 * questions, so the questions live in
 * `tools/ryeong_official_v5/contracts/cross_implementation_cases.json` and each side answers them in
 * its own code.
 *
 * This test answers them with [RyeongV5Findings] and writes the answers where
 * `compare_cross_implementation.py` can put them beside Python's. It also checks each answer against
 * the case's own expectation, so a case list that both implementations get equally wrong is still a
 * failure here.
 */
class V5CrossImplementationCasesTest {

    private val json = Json

    @Test
    fun `the kotlin evaluator answers the shared case list as the contract requires`() {
        val casesFile = EvidenceRoot.file(CASES_PATH)
        assertTrue("shared case list missing: $CASES_PATH", casesFile.isFile)

        val document = json.parseToJsonElement(casesFile.readText(Charsets.UTF_8)) as JsonObject
        val store = V5ContactStore(
            (document["store"] as JsonArray).map { element ->
                val card = element as JsonObject
                V5ContactStore.Card(
                    id = card.str("id").orEmpty(),
                    email = card.str("email").orEmpty(),
                    phone = card.str("phone").orEmpty(),
                    mobile = card.str("mobile").orEmpty(),
                )
            },
        )
        val contract = V5ContactDependencyContract.load()
        val semantics = ToolSemanticCompletion.load()

        val verdicts = mutableListOf<String>()
        val mismatches = mutableListOf<String>()

        (document["cases"] as JsonArray).forEach { element ->
            val case = element as JsonObject
            val identifier = case.str("id").orEmpty()
            val turn = case["turn"] as JsonObject
            val calls = (turn["tool_arguments"] as JsonArray).map { call ->
                val entry = call as JsonObject
                entry.str("tool").orEmpty() to (entry["arguments"] as JsonObject)
            }
            val observation = V5TurnObservation(
                scenarioIndex = turn.int("scenario") ?: 0,
                depth = turn.int("depth") ?: 1,
                kind = "cross-implementation",
                question = turn.str("question").orEmpty(),
                expectedRoute = turn.str("expected_route"),
                noCardsOutOfScope = turn.bool("no_cards_out_of_scope") ?: false,
                outcomeType = turn.str("outcome"),
                answer = turn.str("answer").orEmpty(),
                executedTools = calls.map { it.first },
                toolArguments = calls,
                selectedCardId = turn.str("selected_card_id"),
                previousFocusCardId = turn.str("previous_focus_card_id"),
                utteranceReferencesPreviousFocus = false,
                unverifiedTargetUses = emptyList(),
                leakedIntoActionArguments = emptyList(),
                sessionGeneration = turn.long("session_generation") ?: 0L,
            )
            val prior = (case["prior_verifications"] as? JsonArray).orEmpty().map { entry ->
                val record = entry as JsonObject
                V5Verification(
                    cardId = record.str("card_id").orEmpty(),
                    turnKey = record.str("turn_key").orEmpty(),
                    targetEpoch = record.int("target_epoch") ?: 0,
                    callIndex = record.int("call_index") ?: 0,
                    sessionGeneration = record.long("session_generation") ?: 0L,
                )
            }

            val findings = RyeongV5Findings
                .evaluateTurn(observation, store, contract, semantics, prior)
                .filter { it.code == "ACTION_WITHOUT_CONTACT_READ" }

            fun collect(prefix: String): List<String> = findings
                .flatMap { it.provenance }
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .distinct()
                .sorted()

            val codes = collect("missing_verification=")
            val paths = collect("argument_json_path=")
            val cards = collect("source_card_id=")

            verdicts += J.obj(
                "id" to J.q(identifier),
                "finding_count" to J.num(findings.size),
                "missing_verification_codes" to J.strArr(codes),
                "argument_paths" to J.strArr(paths),
                "source_card_ids" to J.strArr(cards),
            )

            val expect = case["expect"] as JsonObject
            val expectedFindings = expect.int("findings")
            val expectedCode = expect.str("missing_verification")
            when {
                expectedFindings != null && findings.size != expectedFindings ->
                    mismatches += "$identifier: expected $expectedFindings finding(s), got " +
                        "${findings.size} $codes"
                expectedCode != null && expectedCode !in codes ->
                    mismatches += "$identifier: expected $expectedCode, got $codes"
            }
        }

        val out = EvidenceRoot.dir(OUT_DIR)
        File(out, "KOTLIN_CASE_VERDICTS.json").writeText(
            J.obj(
                "schema" to J.q("ryeong_v5_case_verdicts/v1"),
                "implementation" to J.q("kotlin"),
                "judge_path" to J.q(
                    "app/src/test/java/com/example/hjp/v5/V5CrossImplementationCasesTest.kt",
                ),
                "rule_module_sha256" to J.q(
                    sha256(EvidenceRoot.file(FINDINGS_PATH).readBytes()),
                ),
                "cases_path" to J.q(CASES_PATH),
                "cases_sha256" to J.q(sha256(casesFile.readBytes())),
                "contract_sha256" to J.q(V5ContactDependencyContract.contractSha256()),
                "case_count" to J.num(verdicts.size),
                "verdicts" to J.arr(verdicts),
                "expectation_mismatches" to J.strArr(mismatches),
                "matches_expectations" to J.bool(mismatches.isEmpty()),
            ) + "\n",
            Charsets.UTF_8,
        )

        assertEquals("the shared case list should hold every characterized shape", 30, verdicts.size)
        assertEquals("expectation mismatches", emptyList<String>(), mismatches)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonObject.str(name: String): String? {
        val element = this[name] as? JsonPrimitive ?: return null
        if (element is kotlinx.serialization.json.JsonNull) return null
        return element.content
    }

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        const val CASES_PATH = "tools/ryeong_official_v5/contracts/cross_implementation_cases.json"
        const val FINDINGS_PATH = "app/src/test/java/com/example/hjp/eval/v5/RyeongV5Findings.kt"
        const val OUT_DIR =
            "integration_evidence/evaluation/ryeong_official_v5/evaluator_self_test"
    }
}
