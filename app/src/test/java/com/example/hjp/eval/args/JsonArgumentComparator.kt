package com.example.hjp.eval.args

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Compares tool arguments as JSON, recursively, with types intact.
 *
 * The evaluators reached arguments through `Map<String, String>` and string equality. Everything
 * nested therefore went unchecked: `updates` is an object, `clear_fields` and `attendee_emails` are
 * arrays, and flattening them to text made `{"memo":"VIP"}` and `{"memo":"vip","tags":"x"}`
 * distinguishable only by luck. Worse, `"1"` and `1`, `"true"` and `true`, and a missing key versus an
 * explicit `null` all compared equal once stringified — so a model that returned the wrong *type*
 * scored as correct.
 *
 * Every mismatch carries the JSON path it happened at (`updates.memo`, `attendee_emails[1]`), because
 * "arguments differ" is not something a reader can act on.
 *
 * ## Array policy
 *
 * Ordered by default: a tool sequence and a list of positional values mean different things when
 * reordered. Two fields are unordered *by contract* — `clear_fields` and `attendee_emails` — because
 * the schema gives their order no meaning. Unordered does not mean forgiving: a missing element, an
 * extra element and a duplicate are each a mismatch, and cardinality is compared, so a comparison
 * cannot be passed by collapsing duplicates.
 */
object JsonArgumentComparator {

    /** How an array at a given path is compared. */
    enum class ArrayPolicy {
        /** Position carries meaning. */
        ORDERED,

        /**
         * Position carries no meaning, but membership and cardinality do.
         *
         * Duplicates are preserved in the comparison rather than collapsed: `["a","a"]` and `["a"]`
         * are different, because a tool asked to clear a field twice is not the same call.
         */
        UNORDERED_EXACT_MULTISET,
    }

    /** What the comparison is allowed to ignore. */
    enum class ExtraKeyPolicy {
        /** Every key in the actual object must be expected. */
        REJECT_UNEXPECTED,

        /** Extra keys are permitted; only the expected subset is compared. */
        ALLOW_UNEXPECTED,
    }

    data class Contract(
        /** Paths whose arrays are unordered by contract. Keys are dotted paths without indices. */
        val unorderedArrays: Set<String> = setOf("clear_fields", "attendee_emails"),
        val extraKeys: ExtraKeyPolicy = ExtraKeyPolicy.REJECT_UNEXPECTED,
        /** Paths that must be absent. Checked separately from value comparison. */
        val forbiddenPaths: Set<String> = emptySet(),
    ) {
        fun policyFor(path: String): ArrayPolicy =
            if (path.substringAfterLast('.') in unorderedArrays || path in unorderedArrays) {
                ArrayPolicy.UNORDERED_EXACT_MULTISET
            } else {
                ArrayPolicy.ORDERED
            }
    }

    /** One difference, at one path, with a reason a reader can act on. */
    data class Mismatch(val path: String, val reason: String, val expected: String, val actual: String)

    /**
     * Compares [actual] against [expected].
     *
     * Returns every mismatch rather than the first: a caller reporting "arguments differ" once has to
     * re-run to find the second problem.
     */
    fun compare(
        expected: JsonElement,
        actual: JsonElement?,
        contract: Contract = Contract(),
        path: String = "",
    ): List<Mismatch> {
        val mismatches = mutableListOf<Mismatch>()
        compareInto(expected, actual, contract, path.ifEmpty { "$" }, mismatches)
        contract.forbiddenPaths.forEach { forbidden ->
            if (valueAt(actual, forbidden) != null) {
                mismatches += Mismatch(forbidden, "forbidden argument is present", "absent", "present")
            }
        }
        return mismatches
    }

    /** The element at a dotted path, or null when the path is absent. Indices are not supported. */
    fun valueAt(root: JsonElement?, dottedPath: String): JsonElement? {
        var current = root
        dottedPath.split('.').filter { it.isNotEmpty() }.forEach { segment ->
            val obj = current as? JsonObject ?: return null
            current = obj[segment] ?: return null
        }
        return current
    }

    private fun compareInto(
        expected: JsonElement,
        actual: JsonElement?,
        contract: Contract,
        path: String,
        into: MutableList<Mismatch>,
    ) {
        if (actual == null) {
            // A missing key is not a null value. Conflating them is how an omitted argument passed.
            into += Mismatch(path, "key is absent", describe(expected), "absent")
            return
        }
        if (kindOf(expected) != kindOf(actual)) {
            into += Mismatch(
                path,
                "type differs: expected ${kindOf(expected)}, actual ${kindOf(actual)}",
                describe(expected),
                describe(actual),
            )
            return
        }
        when (expected) {
            is JsonObject -> compareObjects(expected, actual as JsonObject, contract, path, into)
            is JsonArray -> compareArrays(expected, actual as JsonArray, contract, path, into)
            is JsonPrimitive -> comparePrimitives(expected, actual as JsonPrimitive, path, into)
            JsonNull -> Unit
        }
    }

    private fun compareObjects(
        expected: JsonObject,
        actual: JsonObject,
        contract: Contract,
        path: String,
        into: MutableList<Mismatch>,
    ) {
        // Key order carries no meaning in JSON, so it is not compared.
        expected.keys.sorted().forEach { key ->
            compareInto(expected.getValue(key), actual[key], contract, child(path, key), into)
        }
        if (contract.extraKeys == ExtraKeyPolicy.REJECT_UNEXPECTED) {
            (actual.keys - expected.keys).sorted().forEach { key ->
                into += Mismatch(child(path, key), "unexpected argument", "absent", describe(actual.getValue(key)))
            }
        }
    }

    private fun compareArrays(
        expected: JsonArray,
        actual: JsonArray,
        contract: Contract,
        path: String,
        into: MutableList<Mismatch>,
    ) {
        if (expected.size != actual.size) {
            into += Mismatch(
                path,
                "array size differs: expected ${expected.size}, actual ${actual.size}",
                describe(expected),
                describe(actual),
            )
        }
        when (contract.policyFor(path)) {
            ArrayPolicy.ORDERED ->
                expected.indices.forEach { index ->
                    compareInto(expected[index], actual.getOrNull(index), contract, "$path[$index]", into)
                }

            ArrayPolicy.UNORDERED_EXACT_MULTISET -> {
                // Multiset comparison, so a duplicate is a difference rather than a no-op.
                val expectedCounts = expected.groupingBy(::describe).eachCount()
                val actualCounts = actual.groupingBy(::describe).eachCount()
                (expectedCounts.keys + actualCounts.keys).sorted().forEach { value ->
                    val want = expectedCounts[value] ?: 0
                    val have = actualCounts[value] ?: 0
                    if (want != have) {
                        into += Mismatch(
                            path,
                            when {
                                have == 0 -> "element missing (unordered contract)"
                                want == 0 -> "unexpected element (unordered contract)"
                                else -> "element occurs $have times, expected $want"
                            },
                            "$value x$want",
                            "$value x$have",
                        )
                    }
                }
            }
        }
    }

    private fun comparePrimitives(
        expected: JsonPrimitive,
        actual: JsonPrimitive,
        path: String,
        into: MutableList<Mismatch>,
    ) {
        // isString separates "1" from 1 and "true" from true. Comparing content alone would not.
        if (expected.isString != actual.isString) {
            into += Mismatch(
                path,
                if (expected.isString) "expected a string, actual a non-string scalar"
                else "expected a non-string scalar, actual a string",
                describe(expected),
                describe(actual),
            )
            return
        }
        if (expected.content != actual.content) {
            into += Mismatch(path, "value differs", describe(expected), describe(actual))
        }
    }

    private fun child(path: String, key: String) = if (path == "$") key else "$path.$key"

    private fun kindOf(element: JsonElement): String = when (element) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        JsonNull -> "null"
        is JsonPrimitive -> when {
            element.isString -> "string"
            element.content == "true" || element.content == "false" -> "boolean"
            else -> "number"
        }
    }

    private fun describe(element: JsonElement): String = when (element) {
        is JsonPrimitive -> if (element.isString) "\"${element.content}\"" else element.content
        else -> element.toString()
    }
}
