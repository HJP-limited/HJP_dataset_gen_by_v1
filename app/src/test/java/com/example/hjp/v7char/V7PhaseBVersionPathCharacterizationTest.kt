package com.example.hjp.v7char

import com.example.hjp.v7char.V7CharacterizationFixture.PATH_POLICY
import com.example.hjp.v7char.V7CharacterizationFixture.V6_TEMPLATE
import com.example.hjp.v7char.V7CharacterizationFixture.V7_COMMANDS
import com.example.hjp.v7char.V7CharacterizationFixture.V7_TEMPLATE
import com.example.hjp.v7char.V7CharacterizationFixture.everyString
import com.example.hjp.v7char.V7CharacterizationFixture.file
import com.example.hjp.v7char.V7CharacterizationFixture.placeholderHints
import com.example.hjp.v7char.V7CharacterizationFixture.requireObject
import com.example.hjp.v7char.V7CharacterizationFixture.stringOf
import com.example.hjp.v7char.V7CharacterizationFixture.strings
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defect B, as a test — and as a policy rather than as three strings.
 *
 * The v6 manifest template hints at `DEVICE_SCORE.json`, `DEVICE_RECOMPUTATION.json` and the v5
 * device namespace in positions an operator acts on. The v6 command sheet writes neither of those
 * two files; they are the names the *v5* procedure used. An operator filling the template in good
 * faith goes looking for artefacts that do not exist.
 *
 * Searching for those three strings would pass a v7 template that got three different ones wrong,
 * so this test is written against the rule instead: an operational position may name only the
 * current version's namespaces and only artefacts the current version's procedure declares. Prose,
 * provenance, protected history and the forbidden list may name whatever they need to.
 *
 * This is a second, independent implementation of the same policy the Python validator applies.
 * Two implementations that agree is the point; one implementation checked against itself is not.
 */
class V7PhaseBVersionPathCharacterizationTest {

    private data class Violation(val pointer: String, val code: String, val names: String)

    private fun policy(): JsonObject = requireObject(PATH_POLICY)

    /** Pointer prefixes a previous version may legitimately appear under. */
    private fun allowedPrefixes(document: JsonObject): List<String> =
        strings(document["allowed_pointer_prefixes"])

    private fun operationalPrefixes(document: JsonObject): List<String> =
        strings(document["operational_pointer_prefixes"])

    private fun previousVersionTokens(document: JsonObject, current: String): List<String> =
        strings(document["previous_version_tokens"]).filterNot { it.equals(current, true) }

    /**
     * A version token, matched as a token rather than as a substring: `rev5` is a build fingerprint,
     * `_v5_` and `/v5/` and `V5Test` are references.
     */
    private fun mentionsVersion(text: String, token: String): Boolean {
        val lower = text.lowercase()
        val needle = token.lowercase()
        var index = lower.indexOf(needle)
        while (index >= 0) {
            val before = if (index == 0) ' ' else lower[index - 1]
            val afterIndex = index + needle.length
            val after = if (afterIndex >= lower.length) ' ' else lower[afterIndex]
            val boundedLeft = !before.isLetterOrDigit()
            val boundedRight = !after.isLetterOrDigit()
            if (boundedLeft && boundedRight) return true
            index = lower.indexOf(needle, index + 1)
        }
        return false
    }

    private fun isAllowedPosition(pointer: String, document: JsonObject): Boolean =
        allowedPrefixes(document).any { pointer == it || pointer.startsWith("$it/") }

    private fun currentArtifacts(document: JsonObject): Set<String> =
        strings(document["current_version_artifacts"]).toSet()

    private fun previousArtifacts(document: JsonObject): Map<String, String> =
        (document["previous_version_artifacts"] as? JsonObject)
            ?.mapValues { stringOf(it.value).orEmpty() }.orEmpty()

    private fun scan(templateRelative: String, currentVersion: String): List<Violation> {
        val document = policy()
        val template = requireObject(templateRelative)
        val found = mutableListOf<Violation>()
        val previous = previousVersionTokens(document, currentVersion)
        val declared = currentArtifacts(document)
        val retired = previousArtifacts(document)

        everyString(template).forEach { (pointer, text) ->
            if (isAllowedPosition(pointer, document)) return@forEach
            val operational = operationalPrefixes(document)
                .any { pointer == it || pointer.startsWith("$it/") } ||
                operationalPrefixes(document).isEmpty()
            if (!operational) return@forEach
            previous.forEach { token ->
                if (mentionsVersion(text, token)) {
                    found += Violation(pointer, "PREVIOUS_VERSION_IN_OPERATIONAL_POSITION", token)
                }
            }
            retired.forEach { (name, _) ->
                if (text.contains(name)) {
                    found += Violation(pointer, "UNDECLARED_ARTIFACT_REFERENCE", name)
                }
            }
            Regex("[A-Za-z0-9_.\\-]+\\.(json|jsonl|py|kt|apk|md|txt|log)").findAll(text)
                .map { it.value }
                .filter { it !in declared && it !in retired }
                .forEach { found += Violation(pointer, "UNDECLARED_ARTIFACT_REFERENCE", it) }
        }
        return found.distinct()
    }

    @Test
    fun `the v6 template names another version in three operational positions`() {
        val found = scan(V6_TEMPLATE, currentVersion = "v6")
        assertEquals(
            "the v6 template's defect is not the three positions it was described as: $found",
            3, found.size,
        )
        assertTrue(
            "the score verdict hint no longer names DEVICE_SCORE.json: $found",
            found.any {
                it.pointer == "/host_analysis/score_verdict" &&
                    it.code == "UNDECLARED_ARTIFACT_REFERENCE" && it.names == "DEVICE_SCORE.json"
            },
        )
        assertTrue(
            "the recomputation hint no longer names DEVICE_RECOMPUTATION.json: $found",
            found.any {
                it.pointer == "/host_analysis/recomputation_agrees" &&
                    it.code == "UNDECLARED_ARTIFACT_REFERENCE" &&
                    it.names == "DEVICE_RECOMPUTATION.json"
            },
        )
        assertTrue(
            "the preflight hint no longer names the v5 device namespace: $found",
            found.any {
                it.pointer == "/preflight/official_v5_namespace_untouched" &&
                    it.code == "PREVIOUS_VERSION_IN_OPERATIONAL_POSITION"
            },
        )
    }

    @Test
    fun `the v6 command sheet never writes the two files its template hints at`() {
        val commands = file(
            "integration_evidence/evaluation/ryeong_official_v6/phase_b_template/PHASE_B_COMMANDS.md",
        ).readText(Charsets.UTF_8)
        listOf("DEVICE_SCORE.json", "DEVICE_RECOMPUTATION.json").forEach { name ->
            assertTrue(
                "$name turns out to be part of the v6 procedure after all, so the template was right",
                !commands.contains(name),
            )
        }
    }

    @Test
    fun `the v7 template names nothing from an earlier version in an operational position`() {
        val found = scan(V7_TEMPLATE, currentVersion = "v7")
        assertEquals("the v7 template carries operational references to another version: $found",
            emptyList<Violation>(), found)
    }

    @Test
    fun `every v7 placeholder hint names an artefact the v7 procedure declares`() {
        val document = policy()
        val declared = currentArtifacts(document)
        val retired = previousArtifacts(document)
        val hints = placeholderHints(requireObject(V7_TEMPLATE))
        assertTrue("the v7 template has no placeholder hints at all", hints.isNotEmpty())
        hints.forEach { (pointer, hint) ->
            Regex("[A-Za-z0-9_.\\-]+\\.(json|jsonl|py|kt|apk|md|txt|log)").findAll(hint)
                .map { it.value }
                .forEach { name ->
                    assertTrue(
                        "$pointer hints at $name, which belongs to ${retired[name] ?: "no declared version"}",
                        name in declared,
                    )
                }
        }
    }

    @Test
    fun `the v7 command sheet and the v7 template name the same artefacts`() {
        val document = policy()
        val declared = currentArtifacts(document)
        val commands = file(V7_COMMANDS)
        assertTrue("the v7 command sheet is not in the tree", commands.isFile)
        val text = commands.readText(Charsets.UTF_8)
        val retired = previousArtifacts(document)
        retired.forEach { (name, version) ->
            assertTrue(
                "the v7 command sheet names $name, which belongs to $version",
                !Regex("(^|[^A-Za-z0-9_.\\-])" + Regex.escape(name)).containsMatchIn(text) ||
                    text.contains("forbidden") || text.contains("historical"),
            )
        }
        val hinted = placeholderHints(requireObject(V7_TEMPLATE))
            .flatMap { (_, hint) ->
                Regex("[A-Za-z0-9_.\\-]+\\.(json|jsonl)").findAll(hint).map { it.value }.toList()
            }
            .distinct()
        assertTrue("the v7 template hints at no result artefacts at all", hinted.isNotEmpty())
        hinted.forEach { name ->
            assertTrue("$name is hinted at but is not a declared v7 artefact", name in declared)
            assertTrue(
                "$name is hinted at in the template and never appears in the command sheet",
                text.contains(name),
            )
        }
    }

    @Test
    fun `the policy declares the positions in which an earlier version may be named`() {
        val document = policy()
        assertEquals("v7", stringOf(document["current_version"]))
        val allowed = allowedPrefixes(document)
        assertNotNull(allowed)
        listOf("/historical_protected_evidence", "/provenance", "/forbidden_in_phase_b",
            "/migration_note").forEach { prefix ->
            assertTrue("$prefix is not an allowed position in the policy", allowed.contains(prefix))
        }
        listOf("/instrumentation/command", "/device_official_namespace").forEach { pointer ->
            assertTrue(
                "$pointer must not be an allowed position",
                allowed.none { pointer == it || pointer.startsWith("$it/") },
            )
        }
        assertTrue(
            "the policy must refuse absolute, traversal, symlink and malformed relative paths",
            strings(document["refused_path_shapes"]).containsAll(
                listOf("absolute", "traversal", "symlink", "malformed_relative"),
            ),
        )
    }
}
