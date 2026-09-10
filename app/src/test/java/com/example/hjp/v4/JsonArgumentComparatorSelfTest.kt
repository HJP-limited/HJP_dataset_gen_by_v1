package com.example.hjp.v4

import com.example.hjp.eval.EvalOutputPolicy
import com.example.hjp.eval.args.JsonArgumentComparator
import com.example.hjp.eval.args.JsonArgumentComparator.Contract
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparator's own tests: each mutation must be caught, and each clean case must pass.
 *
 * A comparator is only as good as the differences it refuses to accept, so this is written as a
 * kill-list. Every case below is a single mutation of one clean payload, and the assertion is that the
 * comparator reports it *at the right path*. The clean payload is checked too — a comparator that
 * rejects everything kills every mutant and is useless.
 */
class JsonArgumentComparatorSelfTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    /** A realistic update call: nested object, unordered array, mixed scalar types. */
    private val expected = json(
        """
        {
          "card_id": "C001",
          "updates": { "memo": "우선연락", "title": "팀장", "priority": 1, "verified": true },
          "clear_fields": ["tags", "website"],
          "attendee_emails": ["a@example.net", "b@example.net"],
          "note": null
        }
        """.trimIndent(),
    )

    private data class Mutant(val id: String, val payload: String, val expectedPath: String)

    private val mutants = listOf(
        Mutant("nested_value_changed",
            """{"card_id":"C001","updates":{"memo":"VIP","title":"팀장","priority":1,"verified":true},"clear_fields":["tags","website"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
            "updates.memo"),
        Mutant("nested_key_missing",
            """{"card_id":"C001","updates":{"title":"팀장","priority":1,"verified":true},"clear_fields":["tags","website"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
            "updates.memo"),
        Mutant("nested_extra_key",
            """{"card_id":"C001","updates":{"memo":"우선연락","title":"팀장","priority":1,"verified":true,"department":"영업"},"clear_fields":["tags","website"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
            "updates.department"),
        Mutant("number_as_string",
            """{"card_id":"C001","updates":{"memo":"우선연락","title":"팀장","priority":"1","verified":true},"clear_fields":["tags","website"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
            "updates.priority"),
        Mutant("boolean_as_string",
            """{"card_id":"C001","updates":{"memo":"우선연락","title":"팀장","priority":1,"verified":"true"},"clear_fields":["tags","website"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
            "updates.verified"),
        Mutant("null_vs_missing",
            """{"card_id":"C001","updates":{"memo":"우선연락","title":"팀장","priority":1,"verified":true},"clear_fields":["tags","website"],"attendee_emails":["a@example.net","b@example.net"]}""",
            "note"),
        Mutant("clear_fields_element_missing",
            """{"card_id":"C001","updates":{"memo":"우선연락","title":"팀장","priority":1,"verified":true},"clear_fields":["tags"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
            "clear_fields"),
        Mutant("clear_fields_extra_element",
            """{"card_id":"C001","updates":{"memo":"우선연락","title":"팀장","priority":1,"verified":true},"clear_fields":["tags","website","memo"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
            "clear_fields"),
        Mutant("clear_fields_duplicate",
            """{"card_id":"C001","updates":{"memo":"우선연락","title":"팀장","priority":1,"verified":true},"clear_fields":["tags","tags"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
            "clear_fields"),
        Mutant("attendee_missing",
            """{"card_id":"C001","updates":{"memo":"우선연락","title":"팀장","priority":1,"verified":true},"clear_fields":["tags","website"],"attendee_emails":["a@example.net"],"note":null}""",
            "attendee_emails"),
        Mutant("attendee_duplicate",
            """{"card_id":"C001","updates":{"memo":"우선연락","title":"팀장","priority":1,"verified":true},"clear_fields":["tags","website"],"attendee_emails":["a@example.net","a@example.net"],"note":null}""",
            "attendee_emails"),
        Mutant("wrong_card_id",
            """{"card_id":"C002","updates":{"memo":"우선연락","title":"팀장","priority":1,"verified":true},"clear_fields":["tags","website"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
            "card_id"),
        Mutant("object_where_scalar_expected",
            """{"card_id":{"value":"C001"},"updates":{"memo":"우선연락","title":"팀장","priority":1,"verified":true},"clear_fields":["tags","website"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
            "card_id"),
        Mutant("array_where_object_expected",
            """{"card_id":"C001","updates":["memo"],"clear_fields":["tags","website"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
            "updates"),
    )

    @Test
    fun `the clean payload compares equal`() {
        assertEquals(
            "an unmutated payload must produce no mismatch, or every mutant below is killed for the " +
                "wrong reason",
            emptyList<JsonArgumentComparator.Mismatch>(),
            JsonArgumentComparator.compare(expected, expected),
        )
    }

    @Test
    fun `key order is not a difference`() {
        val reordered = json(
            """{"note":null,"attendee_emails":["a@example.net","b@example.net"],"clear_fields":["tags","website"],"updates":{"verified":true,"priority":1,"title":"팀장","memo":"우선연락"},"card_id":"C001"}""",
        )
        assertEquals(emptyList<JsonArgumentComparator.Mismatch>(),
            JsonArgumentComparator.compare(expected, reordered))
    }

    @Test
    fun `unordered contract fields ignore order but not membership`() {
        val reorderedArrays = json(
            """{"card_id":"C001","updates":{"memo":"우선연락","title":"팀장","priority":1,"verified":true},"clear_fields":["website","tags"],"attendee_emails":["b@example.net","a@example.net"],"note":null}""",
        )
        assertEquals(
            "clear_fields and attendee_emails have no order in the schema",
            emptyList<JsonArgumentComparator.Mismatch>(),
            JsonArgumentComparator.compare(expected, reorderedArrays),
        )
    }

    @Test
    fun `an ordered array is not reorderable`() {
        val contract = Contract(unorderedArrays = emptySet())
        val reordered = json("""{"tools":["get_contact","search_contacts"]}""")
        val original = json("""{"tools":["search_contacts","get_contact"]}""")
        val mismatches = JsonArgumentComparator.compare(original, reordered, contract)
        assertTrue(
            "a tool sequence reordered is a different sequence: $mismatches",
            mismatches.any { it.path.startsWith("tools[") },
        )
    }

    @Test
    fun `every mutant is killed, at the right path`() {
        val survivors = mutableListOf<String>()
        val wrongPath = mutableListOf<String>()
        val report = mutableListOf<Triple<String, Boolean, String>>()

        mutants.forEach { mutant ->
            val mismatches = JsonArgumentComparator.compare(expected, json(mutant.payload))
            val killed = mismatches.isNotEmpty()
            val paths = mismatches.map { it.path }
            if (!killed) survivors += mutant.id
            else if (paths.none { it == mutant.expectedPath || it.startsWith("${mutant.expectedPath}[") }) {
                wrongPath += "${mutant.id}: expected a mismatch at ${mutant.expectedPath}, got $paths"
            }
            report += Triple(mutant.id, killed, mismatches.joinToString("; ") { "${it.path}: ${it.reason}" })
        }

        assertEquals("no mutant may survive", emptyList<String>(), survivors)
        assertEquals("each mutant must be reported where it was injected", emptyList<String>(), wrongPath)
        write(report)
    }

    @Test
    fun `a forbidden path is reported even when everything else matches`() {
        val contract = Contract(
            extraKeys = JsonArgumentComparator.ExtraKeyPolicy.ALLOW_UNEXPECTED,
            forbiddenPaths = setOf("updates.email"),
        )
        val withForbidden = json(
            """{"card_id":"C001","updates":{"memo":"우선연락","title":"팀장","priority":1,"verified":true,"email":"x@example.net"},"clear_fields":["tags","website"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
        )
        val mismatches = JsonArgumentComparator.compare(expected, withForbidden, contract)
        assertTrue(
            "a forbidden nested argument must be reported even under ALLOW_UNEXPECTED: $mismatches",
            mismatches.any { it.path == "updates.email" && it.reason.contains("forbidden") },
        )
    }

    @Test
    fun `allowing unexpected keys does not weaken the expected ones`() {
        val contract = Contract(extraKeys = JsonArgumentComparator.ExtraKeyPolicy.ALLOW_UNEXPECTED)
        val extraPlusWrong = json(
            """{"card_id":"C001","updates":{"memo":"VIP","title":"팀장","priority":1,"verified":true,"department":"영업"},"clear_fields":["tags","website"],"attendee_emails":["a@example.net","b@example.net"],"note":null}""",
        )
        val mismatches = JsonArgumentComparator.compare(expected, extraPlusWrong, contract)
        assertTrue(
            "the extra key is tolerated but the wrong memo is not: $mismatches",
            mismatches.any { it.path == "updates.memo" } && mismatches.none { it.path == "updates.department" },
        )
    }

    private fun write(report: List<Triple<String, Boolean, String>>) {
        val directory = File(EvalOutputPolicy.outputDir(), "evaluator")
        directory.mkdirs()
        fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        File(directory, "recursive_json_self_test.json").writeText(
            buildString {
                append("{\n")
                append("  ${q("component")}: ${q("JsonArgumentComparator")},\n")
                append("  ${q("mutants")}: ${report.size},\n")
                append("  ${q("killed")}: ${report.count { it.second }},\n")
                append("  ${q("survivors")}: ${report.count { !it.second }},\n")
                append("  ${q("cases")}: [\n")
                report.forEachIndexed { index, (id, killed, detail) ->
                    append("    {${q("id")}: ${q(id)}, ${q("killed")}: $killed, ")
                    append("${q("mismatches")}: ${q(detail)}}")
                    append(if (index == report.lastIndex) "\n" else ",\n")
                }
                append("  ]\n}\n")
            },
        )
    }
}
