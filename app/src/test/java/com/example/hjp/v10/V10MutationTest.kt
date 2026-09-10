package com.example.hjp.v10

import com.example.hjp.v10.V10Contracts.MUTATION
import com.example.hjp.v10.V10Contracts.at
import com.example.hjp.v10.V10Contracts.bool
import com.example.hjp.v10.V10Contracts.int
import com.example.hjp.v10.V10Contracts.list
import com.example.hjp.v10.V10Contracts.obj
import com.example.hjp.v10.V10Contracts.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A check that never fails is indistinguishable from no check.
 *
 * So every rule v10 states is broken on purpose, once, and the report says which failure code came
 * back. An undetected mutation is a rule that exists only in prose.
 */
class V10MutationTest {

    private fun report(): JsonObject? = obj(MUTATION)

    @Test
    fun `every mutation was detected`() {
        val document = report()
        assertNotNull("the mutation report must be in the tree", document)
        assertEquals("ALL MUTATIONS DETECTED", str(document?.get("verdict")))
        assertEquals("undetected mutations", emptyList<String>(),
                     list(document?.get("undetected")))
        assertTrue("there must be mutations to detect",
                   (int(document?.get("detected")) ?: 0) > 0)
    }

    @Test
    fun `both families are covered`() {
        val families = (report()?.get("family_counts") as? JsonObject)?.keys.orEmpty()
        assertTrue("the run-set family must be covered; found $families",
                   families.any { it.startsWith("run_set") })
        assertTrue("the lifecycle family must be covered; found $families",
                   families.any { it.startsWith("lifecycle") })
    }

    @Test
    fun `each mutation names the failure code it expected and got it`() {
        val mutations = (report()?.get("mutations") as? JsonArray)
            ?.filterIsInstance<JsonObject>().orEmpty()
        assertTrue("there must be mutations", mutations.isNotEmpty())
        val missing = mutations.filter { str(it["expected_failure_code"]) == null }
            .mapNotNull { str(it["id"]) }
        assertEquals("every mutation must declare the code it expects",
                     emptyList<String>(), missing)
        val undetected = mutations.filter { bool(it["detected"]) != true }
            .mapNotNull { str(it["id"]) }
        assertEquals("undetected", emptyList<String>(), undetected)
        val wrongCode = mutations.filter {
            val expected = str(it["expected_failure_code"])
            expected != null && !list(it["codes_raised"]).contains(expected)
        }.mapNotNull { str(it["id"]) }
        assertEquals("a mutation detected by the wrong check is not the check working",
                     emptyList<String>(), wrongCode)
    }

    @Test
    fun `the run-set mutations cover every run the registry declares`() {
        val mutations = (report()?.get("mutations") as? JsonArray)
            ?.filterIsInstance<JsonObject>().orEmpty()
        val ids = mutations.mapNotNull { str(it["id"]) }
        // Which runs must be covered is a registry fact. Naming them here would be a second list,
        // and a mutation suite that misses the run nobody remembered is the failure this version is
        // about.
        val declared = V10Contracts.registryRuns().keys
        assertTrue("the registry must declare runs", declared.isNotEmpty())
        val uncovered = declared.filterNot { run -> ids.any { it.contains(run) } }.sorted()
        assertEquals("every declared run must be the subject of at least one mutation; ids are $ids",
                     emptyList<String>(), uncovered)
    }

    @Test
    fun `the lifecycle mutations cover all three v9 deviations`() {
        val ids = ((report()?.get("mutations") as? JsonArray)
            ?.filterIsInstance<JsonObject>().orEmpty()).mapNotNull { str(it["id"]) }
        listOf("frozen_source", "frozen_root_add", "pre_run_overwrite").forEach { shape ->
            assertTrue("a mutation must reproduce $shape; ids are $ids",
                       ids.any { it.contains(shape) })
        }
    }

    @Test
    fun `the tree was restored after every mutation`() {
        assertEquals("every mutation must be undone", true,
                     bool(at(report(), "restoration/all_restored")))
        assertEquals("no file may be left modified", 0,
                     int(at(report(), "restoration/still_modified")))
    }
}
