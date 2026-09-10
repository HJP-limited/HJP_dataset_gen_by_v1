package com.example.hjp.v8

import com.example.hjp.eval.ryeong2.EvidenceRoot
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Count a template's path-policy violations twice: once per position, once per target.
 *
 * v7 asked "how many things is this template wrong about", got the right answer — three — and stored
 * it in a field that means "how many places must an operator fix". Those are five. Neither number is
 * derivable from the other, and a reader that publishes one under a name suggesting the other will
 * eventually be read as the other.
 *
 * So this publishes both, under names that cannot be confused, plus the mapping between them:
 *
 *     operationalOccurrenceCount    one per offending JSON pointer. What an operator must edit.
 *     distinctInvalidTargetCount    one per normalised target. What the document is wrong about.
 *     pointerToTarget               which position names which target — the join that makes the two
 *                                   counts checkable against each other rather than merely asserted
 *                                   side by side.
 *
 * Neither number is a constant. Both come from walking the document, and [selfCheck] proves it on
 * three documents whose answers are known by construction — a reader hard-wired to return five and
 * three passes every case built from the real v6 template and fails all three of those.
 *
 * This is the Kotlin half of the pair. `tools/ryeong_official_v8/cardinality_scanner_v8.py` is the
 * other, written separately against the same policy document. Two implementations that agree is the
 * point; one implementation checked against itself is not.
 */
object V8Cardinality {

    const val READER_VERSION = "ryeong-v8-cardinality-kotlin-1"

    const val CODE_PREVIOUS_VERSION = "PREVIOUS_VERSION_IN_OPERATIONAL_POSITION"
    const val CODE_UNDECLARED_ARTIFACT = "UNDECLARED_ARTIFACT_REFERENCE"

    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private val artifactName = Regex("[A-Za-z0-9_.\\-]+\\.(json|jsonl|py|kt|apk|md|txt|log)")

    data class Occurrence(
        val jsonPointer: String,
        val positionClassification: String,
        val detectedToken: String,
        val normalizedTarget: String,
        val violationCode: String,
        val sourceValue: String,
        val whyOperational: String,
        val belongsTo: String? = null,
    )

    data class Report(
        val target: String,
        val asVersion: String,
        val targetSha256: String?,
        val occurrences: List<Occurrence>,
        val checkedStrings: Int,
        val allowedPositionStrings: Int,
        val duplicatesDropped: List<String>,
    ) {
        val operationalOccurrenceCount: Int get() = occurrences.size
        val operationalOccurrencePointers: List<String> get() = occurrences.map { it.jsonPointer }
        val distinctInvalidTargets: List<String>
            get() = occurrences.map { it.normalizedTarget }.distinct().sorted()
        val distinctInvalidTargetCount: Int get() = distinctInvalidTargets.size
        val pointerToTarget: Map<String, String>
            get() = occurrences.associate { it.jsonPointer to it.normalizedTarget }
        val targetToPointers: Map<String, List<String>>
            get() = occurrences.groupBy { it.normalizedTarget }
                .mapValues { (_, rows) -> rows.map { it.jsonPointer }.sorted() }
                .toSortedMap()
        val pointerListHasNoDuplicates: Boolean
            get() = operationalOccurrencePointers.size ==
                operationalOccurrencePointers.distinct().size
    }

    fun file(relative: String): File = EvidenceRoot.file(relative)

    fun sha256(relative: String): String? {
        val target = file(relative)
        if (!target.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stringOf(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.let { if (it.isString) it.contentOrNull else null }

    private fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { stringOf(it) }.orEmpty()

    /**
     * Every string value in the document, as (json pointer, value), in document order.
     *
     * Document order matters: it is what the contract's ordering policy names, and it is what lets
     * two readers agree on a *list* rather than only on a set.
     */
    fun everyString(root: JsonElement): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        fun walk(element: JsonElement, pointer: String) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) -> walk(value, "$pointer/$key") }
                is JsonArray ->
                    element.forEachIndexed { index, value -> walk(value, "$pointer/$index") }
                is JsonPrimitive -> stringOf(element)?.let { found += pointer to it }
                else -> Unit
            }
        }
        walk(root, "")
        return found
    }

    /**
     * A version token matched as a token: both boundaries non-alphanumeric.
     *
     * `rev5` in a build fingerprint is not a v5 reference. `_v5_`, `/v5/` and `-v5.` are.
     */
    fun mentionsVersion(text: String, token: String): Boolean {
        val lower = text.lowercase()
        val needle = token.lowercase()
        var index = lower.indexOf(needle)
        while (index >= 0) {
            val before = if (index == 0) ' ' else lower[index - 1]
            val afterIndex = index + needle.length
            val after = if (afterIndex >= lower.length) ' ' else lower[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            index = lower.indexOf(needle, index + 1)
        }
        return false
    }

    /**
     * OPERATIONAL or ALLOWED, and why.
     *
     * Fail-closed: a position nobody declared is operational. The alternative is a validator that
     * ignores exactly the cases nobody anticipated, which is the defect wearing a validator's coat.
     */
    fun classifyPosition(pointer: String, policy: JsonObject): Triple<String, String?, String> {
        strings(policy["allowed_pointer_prefixes"]).forEach { prefix ->
            if (pointer == prefix || pointer.startsWith("$prefix/")) {
                val reason = (policy["allowed_pointer_prefix_reasons"] as? JsonObject)
                    ?.get(prefix)?.let { stringOf(it) }
                return Triple("ALLOWED", prefix, reason ?: "declared as an allowed position")
            }
        }
        val operational = strings(policy["operational_pointer_prefixes"])
        if (operational.isEmpty()) {
            return Triple(
                "OPERATIONAL", null,
                "fail-closed default: the policy declares no operational prefix list, so every " +
                    "position that is not explicitly allowed is operational",
            )
        }
        operational.forEach { prefix ->
            if (pointer == prefix || pointer.startsWith("$prefix/")) {
                return Triple("OPERATIONAL", prefix,
                    "the pointer falls under a declared operational prefix")
            }
        }
        return Triple("OPERATIONAL", null,
            "fail-closed default: the pointer matches no allowed prefix")
    }

    /** Every occurrence in one document, in document order, one per (pointer, code, target). */
    fun scan(
        document: JsonObject,
        policy: JsonObject,
        asVersion: String,
        target: String = "<in-memory>",
        targetSha256: String? = null,
    ): Report {
        val previous = strings(policy["previous_version_tokens"])
            .filterNot { it.equals(asVersion, ignoreCase = true) }
        val declared = strings(policy["current_version_artifacts"]).toSet()
        val retired = (policy["previous_version_artifacts"] as? JsonObject)
            ?.mapValues { stringOf(it.value).orEmpty() }.orEmpty()

        val raw = mutableListOf<Occurrence>()
        var checked = 0
        var allowedPositions = 0

        everyString(document).forEach { (pointer, text) ->
            val (classification, _, why) = classifyPosition(pointer, policy)
            if (classification == "ALLOWED") {
                allowedPositions++
                return@forEach
            }
            checked++
            previous.forEach { token ->
                if (mentionsVersion(text, token)) {
                    raw += Occurrence(pointer, classification, token, token,
                        CODE_PREVIOUS_VERSION, text, why)
                }
            }
            retired.forEach { (name, version) ->
                if (text.contains(name)) {
                    raw += Occurrence(pointer, classification, name, name,
                        CODE_UNDECLARED_ARTIFACT, text, why, belongsTo = version)
                }
            }
            artifactName.findAll(text).map { it.value }.forEach { name ->
                if (name !in declared && name !in retired) {
                    raw += Occurrence(pointer, classification, name, name,
                        CODE_UNDECLARED_ARTIFACT, text, why)
                }
            }
        }

        // One occurrence per (pointer, code, target). Two *different* pointers naming one target
        // stay two occurrences — collapsing them here is the exact step that turned five into three.
        val seen = mutableSetOf<Triple<String, String, String>>()
        val deduped = mutableListOf<Occurrence>()
        val dropped = mutableListOf<String>()
        raw.forEach { row ->
            val key = Triple(row.jsonPointer, row.violationCode, row.normalizedTarget)
            if (!seen.add(key)) {
                dropped += "${row.jsonPointer} ${row.violationCode} ${row.normalizedTarget}"
                return@forEach
            }
            deduped += row
        }

        return Report(target, asVersion, targetSha256, deduped, checked, allowedPositions, dropped)
    }

    fun scanFile(relative: String, policy: JsonObject, asVersion: String): Report? {
        val target = file(relative)
        if (!target.isFile) return null
        val document = json.parseToJsonElement(target.readText(Charsets.UTF_8)) as JsonObject
        return scan(document, policy, asVersion, relative, sha256(relative))
    }

    data class SelfCheckRow(
        val case: String,
        val expectedOccurrences: Int,
        val observedOccurrences: Int,
        val expectedTargets: Int,
        val observedTargets: Int,
    ) {
        val agrees: Boolean
            get() = expectedOccurrences == observedOccurrences && expectedTargets == observedTargets
    }

    /**
     * Three documents whose answers are known by construction.
     *
     * This is what stops the real numbers from being constants. A reader hard-wired to return five
     * and three passes every case built from the v6 template and fails all three of these.
     */
    fun selfCheck(policy: JsonObject): List<SelfCheckRow> {
        fun parse(text: String) = json.parseToJsonElement(text) as JsonObject
        val cases = listOf(
            Triple("empty", """{"schema":"x"}""", 0 to 0),
            Triple("one_pointer_one_target", """{"a":"<from DEVICE_SCORE.json>"}""", 1 to 1),
            Triple(
                "two_pointers_one_target",
                """{"a":"<from DEVICE_SCORE.json>","b":"<also from DEVICE_SCORE.json>"}""",
                2 to 1,
            ),
        )
        return cases.map { (name, text, expected) ->
            val report = scan(parse(text), policy, "v6")
            SelfCheckRow(name, expected.first, report.operationalOccurrenceCount,
                expected.second, report.distinctInvalidTargetCount)
        }
    }
}
