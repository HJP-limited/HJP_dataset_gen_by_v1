package com.example.hjp.v10

import com.example.hjp.eval.ryeong2.EvidenceRoot
import com.example.hjp.v10char.V10CharacterizationFixture
import com.example.hjp.v10.V10Contracts.CONSUMER_CONTRACT
import com.example.hjp.v10.V10Contracts.CONSUMER_INVENTORY
import com.example.hjp.v10.V10Contracts.CROSS_RUN
import com.example.hjp.v10.V10Contracts.KOTLIN_PARITY
import com.example.hjp.v10.V10Contracts.PROJECTIONS
import com.example.hjp.v10.V10Contracts.REGISTRY
import com.example.hjp.v10.V10Contracts.at
import com.example.hjp.v10.V10Contracts.bool
import com.example.hjp.v10.V10Contracts.consumers
import com.example.hjp.v10.V10Contracts.int
import com.example.hjp.v10.V10Contracts.list
import com.example.hjp.v10.V10Contracts.obj
import com.example.hjp.v10.V10Contracts.projectionRuns
import com.example.hjp.v10.V10Contracts.projections
import com.example.hjp.v10.V10Contracts.registryRuns
import com.example.hjp.v10.V10Contracts.requireObj
import com.example.hjp.v10.V10Contracts.sha256
import com.example.hjp.v10.V10Contracts.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is the authority; this checks that every v10 artefact agrees with it.
 *
 * It also *writes* one file — `KOTLIN_PROJECTION_PARITY.json` — because the consumer contract says
 * the Kotlin consumers must report the run set they actually used, and the only way for Kotlin to
 * report that honestly is for Kotlin to write it. Reading the projection JSON and asserting it
 * matches itself would prove nothing; what is written here is what the compiled constants say.
 */
class V10ContractTest {

    private fun kotlinProjection(id: String): List<String> =
        V10RunIdentity.Projections.byId[id]
            ?: error("the generated identity file has no projection named $id")

    @Test
    fun `the registry declares every run with an ordinal, a kind and a lifecycle state`() {
        val runs = registryRuns()
        // The expected set comes from the frozen characterization fixture, which is the oracle
        // this version wrote before any of the implementation existed. Repeating it here would be
        // a second list, and a second list is the thing v10 exists to remove.
        assertEquals(
            "the registry must declare exactly the runs the frozen case oracle accounts for",
            V10CharacterizationFixture.REQUIRED_RUNS.sorted(),
            runs.keys.sorted(),
        )
        runs.forEach { (key, entry) ->
            assertNotNull("$key must declare an ordinal", int(entry["ordinal"]))
            assertNotNull("$key must declare a run_kind", str(entry["run_kind"]))
            assertNotNull("$key must declare a lifecycle_state", str(entry["lifecycle_state"]))
            listOf("include_in_host_comparison", "include_in_identity_parity",
                   "include_in_phase_b", "include_in_protected_history").forEach { field ->
                assertNotNull("$key must declare $field", bool(entry[field]))
            }
        }
        val ordinals = runs.values.mapNotNull { int(it["ordinal"]) }
        assertEquals("ordinals must be unique", ordinals.size, ordinals.distinct().size)
    }

    @Test
    fun `run_order is derived from the ordinals rather than written down`() {
        val registry = requireObj(REGISTRY)
        assertEquals(true, bool(registry["run_order_is_derived"]))
        val expected = registryRuns().entries
            .sortedBy { int(it.value["ordinal"]) }
            .map { it.key }
        assertEquals("run_order must equal the ordinal ordering", expected,
                     list(registry["run_order"]))
    }

    @Test
    fun `K7 is not_run_hold, has no metrics and is carried by the host comparison`() {
        val k7 = registryRuns()["K7"]
        assertEquals("not_run_hold", str(k7?.get("lifecycle_state")))
        assertEquals(0, int(k7?.get("invocations")))
        assertEquals(null, str(k7?.get("metric_source_path")))
        assertTrue("K7 must appear in the host comparison projection",
                   projectionRuns("host_comparison_runs").contains("K7"))
        assertTrue("K7 must not appear among the runs with records",
                   !projectionRuns("host_runs_with_records").contains("K7"))
    }

    @Test
    fun `K8 and K9 keep a run-local validity and a version-level verdict apart`() {
        val runs = registryRuns()
        assertEquals("VALID BASELINE", str(runs["K8"]?.get("run_local_validity")))
        assertEquals("HOLD BEFORE V8 DEVICE PREFLIGHT",
                     str(runs["K8"]?.get("version_level_verdict")))
        assertEquals("VALID BASELINE", str(runs["K9"]?.get("run_local_validity")))
        assertEquals("HOLD BEFORE V9 DEVICE PREFLIGHT",
                     str(runs["K9"]?.get("version_level_verdict")))
    }

    @Test
    fun `K10, D10 and SMOKE are declared before they happen and say so`() {
        val runs = registryRuns()
        val current = projectionRuns("current_version_runs")
        assertTrue("this version must own some runs", current.isNotEmpty())
        current.forEach { key ->
            assertEquals("$key must be declared_future", "declared_future",
                         str(runs[key]?.get("lifecycle_state")))
            assertEquals("$key must have no invocations yet", 0, int(runs[key]?.get("invocations")))
            assertEquals("$key must have no validity yet", null,
                         str(runs[key]?.get("run_local_validity")))
        }
    }

    @Test
    fun `the three v10 run identities do not contain one another`() {
        // The three runs are the ones the registry says belong to this version, not three names
        // typed here.
        val current = projectionRuns("current_version_runs")
        assertEquals("this version must own exactly three runs", 3, current.size)
        val ids = current.mapNotNull { key ->
            val entry = registryRuns()[key]
            str(entry?.get("host_run_id")) ?: str(entry?.get("device_run_id"))
                ?: str(entry?.get("smoke_run_id"))
        }
        assertEquals("every current run must declare an identifier", 3, ids.size)
        assertEquals("the three run ids must be distinct", 3, ids.distinct().size)
        ids.forEach { a ->
            ids.filter { it != a }.forEach { b ->
                assertTrue("$a must not contain $b", !a.contains(b))
            }
        }
        val namespaces = current.mapNotNull { key ->
            val entry = registryRuns()[key]
            str(entry?.get("host_output_namespace"))
                ?: str(entry?.get("device_official_namespace"))
                ?: str(entry?.get("device_smoke_namespace"))
        }
        assertEquals("every current run must declare a namespace", 3, namespaces.size)
        namespaces.forEach { a ->
            namespaces.filter { it != a }.forEach { b ->
                assertTrue("$a must not be a prefix of $b", !a.startsWith("$b/"))
            }
        }
    }

    @Test
    fun `the generated contract's run set is the projection, not a list of its own`() {
        val contract = requireObj(CROSS_RUN)
        assertEquals("host_comparison_runs", str(contract["run_set_projection"]))
        assertEquals(projectionRuns("host_comparison_runs"), list(contract["run_order"]))
        assertEquals(
            "the contract must carry the registry digest it was generated from",
            str(requireObj(REGISTRY)["registry_digest"]), str(contract["registry_digest"]),
        )
        assertEquals(
            "the contract must declare a run entry for every projection member",
            projectionRuns("host_comparison_runs").sorted(),
            (contract["runs"] as? JsonObject)?.keys?.sorted(),
        )
    }

    @Test
    fun `K10's result schema is registered so its record can be read`() {
        val contract = requireObj(CROSS_RUN)
        val schema = str(at(contract, "runs/K10/expected_result_schema"))
        assertEquals("ryeong_v10_official_result/v1", schema)
        val registered = (contract["record_schemas"] as? JsonObject)?.keys.orEmpty()
        assertTrue("the K10 schema must be in record_schemas", registered.contains(schema))
    }

    @Test
    fun `every consumer names a projection and keeps no fallback list`() {
        val declared = consumers()
        assertTrue("there must be consumers", declared.isNotEmpty())
        val known = projections().keys
        declared.forEach { (id, entry) ->
            val projection = str(entry["projection_id"])
            assertNotNull("$id must name a projection", projection)
            assertTrue("$id names an unknown projection: $projection",
                       known.contains(projection))
            assertEquals("$id must not keep a fallback list", false,
                         bool(entry["has_fallback_list"]))
        }
    }

    @Test
    fun `no projection is orphaned and no registry run is outside every projection`() {
        val claimed = consumers().values.mapNotNull { str(it["projection_id"]) }.toSet()
        assertEquals("projections no consumer reads", emptyList<String>(),
                     projections().keys.filterNot { claimed.contains(it) }.sorted())
        val covered = projections().values.flatMap { list(it["runs"]) }.toSet()
        assertEquals("runs in no projection", emptyList<String>(),
                     registryRuns().keys.filterNot { covered.contains(it) }.sorted())
    }

    @Test
    fun `the consumer inventory names the same consumers as the contract`() {
        val inventory = obj(CONSUMER_INVENTORY)
        assertNotNull("the consumer inventory must be in the tree", inventory)
        assertEquals(consumers().keys.sorted(), list(inventory?.get("consumer_ids")).sorted())
    }

    @Test
    fun `the singleton projections resolve to exactly one run each`() {
        // Which projections are singletons is a registry fact, read from the registry.
        val singletons = projections().filterValues {
            str(it["ordering_semantics"]) == "singleton"
        }
        assertTrue("there must be singleton projections", singletons.isNotEmpty())
        singletons.forEach { (id, record) ->
            val resolved = list(record["runs"])
            assertEquals("$id must resolve to exactly one run, got $resolved", 1, resolved.size)
            val run = registryRuns()[resolved.single()]
            assertNotNull("$id resolves to ${resolved.single()}, which the registry does not declare",
                          run)
            assertEquals("a current-version singleton must belong to this version",
                         10, int(run?.get("version")))
        }
    }

    @Test
    fun `the compiled Kotlin projections equal the resolved projections, and it is written down`() {
        val resolved = projections()
        val registryDigest = str(requireObj(REGISTRY)["registry_digest"])
        assertEquals(
            "the generated Kotlin must carry the registry digest it was rendered from",
            registryDigest, V10RunIdentity.REGISTRY_DIGEST,
        )

        val disagreements = mutableListOf<String>()
        resolved.forEach { (id, record) ->
            val fromJson = list(record["runs"])
            val fromKotlin = kotlinProjection(id)
            if (fromJson != fromKotlin) {
                disagreements += "$id: json=$fromJson kotlin=$fromKotlin"
            }
        }
        assertEquals("the compiled constants must equal the resolved projections",
                     emptyList<String>(), disagreements)

        // What the Kotlin side actually used, written by the Kotlin side. The consumer validator
        // reads this file; a Kotlin consumer that reported the JSON back would be reporting the
        // question rather than its answer.
        val body = buildString {
            append("{\n")
            append("  \"schema\": \"ryeong_v10_kotlin_projection_parity/v1\",\n")
            append("  \"written_by\": \"com.example.hjp.v10.V10ContractTest\",\n")
            append("  \"why\": \"the run sets the compiled Kotlin constants hold, reported by the ")
            append("Kotlin side so a consumer's claim is its own rather than a copy of the file ")
            append("it is being checked against\",\n")
            append("  \"registry_digest\": \"${V10RunIdentity.REGISTRY_DIGEST}\",\n")
            append("  \"projections_sha256\": \"${sha256(PROJECTIONS)}\",\n")
            append("  \"registry_sha256\": \"${sha256(REGISTRY)}\",\n")
            append("  \"consumer_contract_sha256\": \"${sha256(CONSUMER_CONTRACT)}\",\n")
            append("  \"agrees_with_resolved_projections\": true,\n")
            append("  \"kotlin\": {\n")
            val ids = V10RunIdentity.Projections.byId.keys.sorted()
            ids.forEachIndexed { index, id ->
                val runs = kotlinProjection(id).joinToString(", ") { "\"$it\"" }
                append("    \"$id\": [$runs]")
                append(if (index == ids.size - 1) "\n" else ",\n")
            }
            append("  }\n")
            append("}\n")
        }
        val target = EvidenceRoot.file(KOTLIN_PARITY)
        target.parentFile?.mkdirs()
        target.writeText(body, Charsets.UTF_8)
        assertTrue("the parity file must have been written", target.isFile)
    }
}
