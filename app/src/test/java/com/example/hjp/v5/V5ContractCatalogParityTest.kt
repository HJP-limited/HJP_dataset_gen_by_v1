package com.example.hjp.v5

import com.example.hjp.eval.ryeong2.ACTION_TOOLS
import com.example.hjp.eval.ryeong2.CONTACT_READ_TOOLS
import com.example.hjp.eval.v4.ToolArgumentContracts
import com.example.hjp.eval.v5.V5ContactDependencyContract
import com.hjp.tool.android.AndroidIntentToolContracts
import com.hjp.tool.contact.ContactToolContracts
import com.hjp.tool.datetime.DateTimeToolContracts
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract talks about the tools production actually has.
 *
 * A contact-dependency contract naming an argument no tool takes would be checking nothing, and
 * would go on checking nothing quietly. So every path the contract declares — target or pure — is
 * matched against the tool's real input schema, read from the production `ToolContract` rather than
 * from a copy.
 *
 * This is also where a *new* production tool becomes a failing test rather than a blind spot: the
 * contract's action set and the evaluator's action set must be the same set, so adding a
 * side-effecting tool without giving it a contact contract cannot pass.
 */
class V5ContractCatalogParityTest {

    private val contract = V5ContactDependencyContract.load()

    private val productionSchemas: Map<String, JsonObject> = mapOf(
        "search_contacts" to ContactToolContracts.Search.inputSchema,
        "get_contact" to ContactToolContracts.Get.inputSchema,
        "update_business_card" to ContactToolContracts.Update.inputSchema,
        "create_calendar_event" to AndroidIntentToolContracts.Calendar.inputSchema,
        "open_compose" to AndroidIntentToolContracts.Compose.inputSchema,
        "get_current_datetime" to DateTimeToolContracts.Current.inputSchema,
    )

    private fun properties(tool: String): Set<String> =
        (productionSchemas.getValue(tool)["properties"] as? JsonObject)?.keys.orEmpty()

    @Test
    fun `the contract's action tools are exactly the side-effecting tools`() {
        assertEquals(ACTION_TOOLS, contract.actionTools.toSet())
    }

    @Test
    fun `the contract's read tools are exactly the contact read tools`() {
        assertEquals(
            CONTACT_READ_TOOLS,
            (contract.rankingOnlyTools + contract.verificationTool).toSet(),
        )
    }

    @Test
    fun `the contract covers every production tool the argument contracts know`() {
        val covered = contract.actionTools.toSet() +
            contract.rankingOnlyTools.toSet() + contract.verificationTool
        val uncovered = ToolArgumentContracts.PRODUCTION_TOOLS - covered
        assertEquals(
            "a production tool with no contact-dependency role declared",
            setOf("get_current_datetime"), uncovered,
        )
    }

    @Test
    fun `every declared contact target is an argument the tool actually takes`() {
        contract.actionTools.forEach { tool ->
            val declared = contract.actionContract(tool)!!
            val schema = properties(tool)
            declared.contactTargetPaths.forEach { target ->
                assertTrue(
                    "$tool declares a contact target '${target.path}' its schema does not have: $schema",
                    target.path in schema,
                )
            }
        }
    }

    @Test
    fun `every declared pure path is an argument the tool actually takes`() {
        contract.actionTools.forEach { tool ->
            val declared = contract.actionContract(tool)!!
            val schema = properties(tool)
            declared.pureActionPaths
                .map { it.substringBefore('.').removeSuffix("[]") }
                .distinct()
                .forEach { path ->
                    assertTrue(
                        "$tool declares a pure path '$path' its schema does not have: $schema",
                        path in schema,
                    )
                }
        }
    }

    @Test
    fun `every argument of every action tool is either a contact target or declared pure`() {
        contract.actionTools.forEach { tool ->
            val declared = contract.actionContract(tool)!!
            val classified = declared.contactTargetPaths.map { it.path }.toSet() +
                declared.pureActionPaths.map { it.substringBefore('.').removeSuffix("[]") }.toSet()
            val unclassified = properties(tool) - classified
            assertEquals(
                "$tool has arguments the contract classifies neither way",
                emptySet<String>(), unclassified,
            )
        }
    }

    @Test
    fun `the verification tool identifies its card by an argument it actually takes`() {
        assertTrue(
            "${contract.verificationTool} has no ${contract.verifiedCardPath} argument",
            contract.verifiedCardPath in properties(contract.verificationTool),
        )
    }

    @Test
    fun `the ranking tool cannot return an address, which is why it is not a verification`() {
        val output = ContactToolContracts.Search.outputSchema
        val results = ((output["properties"] as JsonObject)["results"] as JsonObject)
        val item = (results["items"] as JsonObject)
        val fields = ((item["properties"] as JsonObject)).keys
        listOf("email", "phone", "mobile").forEach { addressable ->
            assertTrue(
                "search_contacts returns $addressable — the contract's reason for treating it as " +
                    "ranking-only no longer holds",
                addressable !in fields,
            )
        }
    }

    @Test
    fun `the addressable fields the contract compares are fields the detail read returns`() {
        val output = ContactToolContracts.Get.outputSchema
        val fields = (output["properties"] as JsonObject).keys
        contract.storeFieldsCompared.filter { it != "id" }.forEach { field ->
            assertTrue("get_contact does not return $field", field in fields)
        }
    }
}
