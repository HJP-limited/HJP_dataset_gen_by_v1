package com.example.hjp.v11

import com.example.hjp.eval.ryeong2.EvidenceRoot
import com.example.hjp.v11.V11Contracts.CONSUMER_CONTRACT
import com.example.hjp.v11.V11Contracts.GATE_CONTRACT
import com.example.hjp.v11.V11Contracts.KOTLIN_PARITY
import com.example.hjp.v11.V11Contracts.PROJECTIONS
import com.example.hjp.v11.V11Contracts.REGISTRY
import com.example.hjp.v11.V11Contracts.at
import com.example.hjp.v11.V11Contracts.bool
import com.example.hjp.v11.V11Contracts.consumers
import com.example.hjp.v11.V11Contracts.forbiddenObservedStateFields
import com.example.hjp.v11.V11Contracts.int
import com.example.hjp.v11.V11Contracts.list
import com.example.hjp.v11.V11Contracts.projectionRuns
import com.example.hjp.v11.V11Contracts.projections
import com.example.hjp.v11.V11Contracts.registryRuns
import com.example.hjp.v11.V11Contracts.requireObj
import com.example.hjp.v11.V11Contracts.sha256
import com.example.hjp.v11.V11Contracts.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is the authority for identity and topology, and for nothing else.
 *
 * This suite also writes `KOTLIN_PROJECTION_PARITY.json`, because the consumer contract asks the
 * Kotlin consumers to report the run set they actually used. Reading the projection file and
 * asserting it matches itself would prove nothing; what is written here is what the compiled
 * constants hold.
 */
class V11ContractTest {

    private fun kotlinProjection(id: String): List<String> =
        V11RunIdentity.Projections.byId[id]
            ?: error("the generated identity file has no projection named $id")

    @Test
    fun `the registry holds no observed state`() {
        val forbidden = forbiddenObservedStateFields()
        assertTrue("the registry must declare which fields it refuses to hold",
                   forbidden.isNotEmpty())
        val offenders = registryRuns().flatMap { (key, entry) ->
            forbidden.filter { entry.containsKey(it) }.map { "$key.$it" }
        }
        assertEquals("the registry may not carry an observation", emptyList<String>(),
                     offenders.sorted())
        assertEquals(true, bool(requireObj(REGISTRY)["identity_and_topology_only"]))
    }

    @Test
    fun `run order is derived from the ordinals rather than written down`() {
        val registry = requireObj(REGISTRY)
        assertEquals(true, bool(registry["run_order_is_derived"]))
        val expected = registryRuns().entries.sortedBy { int(it.value["ordinal"]) }.map { it.key }
        assertEquals(expected, list(registry["run_order"]))
        val ordinals = registryRuns().values.mapNotNull { int(it["ordinal"]) }
        assertEquals("ordinals must be unique", ordinals.size, ordinals.distinct().size)
    }

    @Test
    fun `every historical device identity a past version declared is registered`() {
        val historical = projectionRuns("historical_device_runs")
        assertTrue("earlier versions declared device identities and they must all be here",
                   historical.size >= 8)
        historical.forEach { key ->
            val entry = registryRuns()[key]
            assertNotNull("$key must be declared", entry)
            assertNotNull("$key must name where its state is recorded",
                          str(entry!!["historical_state_authority"]))
        }
    }

    @Test
    fun `the current version owns exactly one host run, one device run and one smoke`() {
        listOf("current_host_run", "current_device_official_run", "current_smoke_run")
            .forEach { id ->
                assertEquals("$id must resolve to one run", 1, projectionRuns(id).size)
                assertEquals("singleton", str(projections()[id]?.get("ordering_semantics")))
            }
        val current = projectionRuns("current_version_runs")
        assertEquals(3, current.size)
        current.forEach { key ->
            assertEquals("$key must belong to this version", true,
                         bool(registryRuns()[key]?.get("is_current_version")))
        }
    }

    @Test
    fun `the three current run identities do not contain one another`() {
        val current = projectionRuns("current_version_runs")
        val ids = current.mapNotNull { str(registryRuns()[it]?.get("run_id")) }
        assertEquals(3, ids.size)
        ids.forEach { a ->
            ids.filter { it != a }.forEach { b ->
                assertTrue("$a must not contain $b", !a.contains(b))
            }
        }
        val namespaces = current.mapNotNull { str(registryRuns()[it]?.get("namespace")) }
        assertEquals(3, namespaces.size)
        namespaces.forEach { a ->
            namespaces.filter { it != a }.forEach { b ->
                assertTrue("$a must not be a path prefix of $b",
                           !a.split('/').let { s -> b.split('/').take(s.size) == s })
            }
        }
    }

    @Test
    fun `a previous official namespace is blocked on a path segment, not a substring`() {
        // v10's runner refused its own namespace because ryeong_official_v1 is a string prefix of
        // ryeong_official_v10. v11's number makes both v1 and v10 prefixes of v11.
        val current = str(registryRuns()["K11"]?.get("namespace"))!!
        val segments = current.split('/')
        // v1 is a string prefix of v11, which is the trap that refused v10's own run. v10 is not,
        // and asserting that it were would be asserting something untrue in order to look thorough.
        assertTrue("ryeong_official_v1 is a substring of $current, which is the trap",
                   current.contains("ryeong_official_v1"))
        assertTrue("ryeong_official_v10 is not a substring of $current",
                   !current.contains("ryeong_official_v10"))
        listOf("ryeong_official_v1", "ryeong_official_v10").forEach { older ->
            assertTrue("$older must not be one of $current's path segments",
                       !segments.contains(older))
        }
    }

    @Test
    fun `every consumer names a projection and keeps no fallback list`() {
        val known = projections().keys
        val declared = consumers()
        assertTrue("there must be consumers", declared.isNotEmpty())
        declared.forEach { (id, entry) ->
            val projection = str(entry["projection_id"])
            assertNotNull("$id must name a projection", projection)
            assertTrue("$id names an unknown projection: $projection", known.contains(projection))
            assertEquals("$id must not keep a fallback list", false,
                         bool(entry["has_fallback_list"]))
        }
    }

    @Test
    fun `no projection is orphaned and no run is outside every counted projection`() {
        val claimed = consumers().values.mapNotNull { str(it["projection_id"]) }.toSet()
        assertEquals("projections no consumer reads", emptyList<String>(),
                     projections().keys.filterNot { claimed.contains(it) }.sorted())
        val counted = projections().filterValues { bool(it["counts_for_coverage"]) != false }
        val covered = counted.values.flatMap { list(it["runs"]) }.toSet()
        assertEquals("runs in no counted projection", emptyList<String>(),
                     registryRuns().keys.filterNot { covered.contains(it) }.sorted())
    }

    @Test
    fun `the gate contract derives its own denominator and covers every blocking code`() {
        val gates = requireObj(GATE_CONTRACT)
        val required = list(gates["required_hard_gates"])
        assertTrue("there must be required gates", required.isNotEmpty())
        assertEquals(required.size, int(gates["required_hard_gate_count"]))
        assertEquals(true, bool(gates["denominator_derived_from_this_contract"]))
        val mapping = at(gates, "blocking_code_to_gate") as JsonObject
        assertTrue("blocking codes must be mapped", mapping.isNotEmpty())
        val uncovered = mapping.filterValues { list(it).isEmpty() }.keys
        assertEquals("every blocking code must name a gate", emptySet<String>(), uncovered)
        val unknownGates = mapping.values.flatMap { list(it) }.filterNot { required.contains(it) }
        assertEquals("a blocking code may not name a gate that is not required",
                     emptyList<String>(), unknownGates.distinct().sorted())
    }

    @Test
    fun `the compiled projections equal the resolved ones, and Kotlin writes down what it used`() {
        val resolved = projections()
        assertEquals("the generated file must carry the registry digest it was rendered from",
                     str(requireObj(REGISTRY)["registry_digest"]), V11RunIdentity.REGISTRY_DIGEST)

        val disagreements = resolved.mapNotNull { (id, record) ->
            val fromJson = list(record["runs"])
            val fromKotlin = kotlinProjection(id)
            if (fromJson == fromKotlin) null else "$id: json=$fromJson kotlin=$fromKotlin"
        }
        assertEquals(emptyList<String>(), disagreements)

        val body = buildString {
            append("{\n")
            append("  \"schema\": \"ryeong_v11_kotlin_projection_parity/v1\",\n")
            append("  \"written_by\": \"com.example.hjp.v11.V11ContractTest\",\n")
            append("  \"why\": \"the run sets the compiled Kotlin constants hold, reported by the ")
            append("Kotlin side so a consumer's claim is its own rather than a copy of the file ")
            append("it is checked against\",\n")
            append("  \"registry_digest\": \"${V11RunIdentity.REGISTRY_DIGEST}\",\n")
            append("  \"registry_sha256\": \"${sha256(REGISTRY)}\",\n")
            append("  \"projections_sha256\": \"${sha256(PROJECTIONS)}\",\n")
            append("  \"consumer_contract_sha256\": \"${sha256(CONSUMER_CONTRACT)}\",\n")
            append("  \"agrees_with_resolved_projections\": true,\n")
            append("  \"kotlin\": {\n")
            val ids = V11RunIdentity.Projections.byId.keys.sorted()
            ids.forEachIndexed { index, id ->
                val runs = kotlinProjection(id).joinToString(", ") { "\"$it\"" }
                append("    \"$id\": [$runs]")
                append(if (index == ids.size - 1) "\n" else ",\n")
            }
            append("  }\n}\n")
        }
        val target = EvidenceRoot.file(KOTLIN_PARITY)
        target.parentFile?.mkdirs()
        target.writeText(body, Charsets.UTF_8)
        assertTrue("the parity file must have been written", target.isFile)
    }
}
