package com.example.hjp

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.CalendarContract
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import com.hjp.agent.contract.ModelToolCall
import com.hjp.agent.core.AgentSessionView
import com.hjp.agent.core.DefaultToolExecutor
import com.hjp.agent.core.DefaultToolPolicyEngine
import com.hjp.agent.core.DefaultToolRegistry
import com.hjp.agent.core.ToolImplementationCandidate
import com.hjp.agent.core.ToolPolicyDecision
import com.hjp.tool.android.AndroidCalendarComposerBackend
import com.hjp.tool.android.AndroidIntentToolContracts
import com.hjp.tool.android.AndroidMessageComposerBackend
import com.hjp.tool.android.CreateCalendarEventPlugin
import com.hjp.tool.android.OpenComposePlugin
import com.hjp.tool.contact.ContactToolContracts
import com.hjp.tool.contact.UpdateBusinessCardPlugin
import com.hjp.tool.contract.CatalogContext
import com.hjp.tool.contract.ToolExecutionContext
import com.hjp.tool.contract.ToolExecutionResult
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-7 verifies real Android/Room implementations, not the Recording backends used by A-6.
 * It intentionally does not make a network request or press Send/Save in the external app.
 */
class ProductionAndroidToolSurfaceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun productionCompositionUsesAndroidIntentAndRoomPlugins() {
        val app = context.applicationContext as HjpApplication
        val container = app.container
        val plugins = privateField(container, "plugins") as List<*>

        val calendar = plugins.filterIsInstance<CreateCalendarEventPlugin>().single()
        val compose = plugins.filterIsInstance<OpenComposePlugin>().single()
        val update = plugins.filterIsInstance<UpdateBusinessCardPlugin>().single()

        assertEquals("android.calendar.intent.v1", calendar.implementationId.value)
        assertEquals("android.message.intent.v1", compose.implementationId.value)
        assertEquals("contact.update.local.v1", update.implementationId.value)
        assertTrue(privateField(calendar, "backend") is AndroidCalendarComposerBackend)
        assertTrue(privateField(compose, "backend") is AndroidMessageComposerBackend)
        assertTrue(privateField(update, "repository") is RoomBusinessCardRepository)
    }

    @Test
    fun actualAndroidIntentPluginsLaunchExternalSurfacesWithExpectedPayload() = runBlocking {
        val calendar = CreateCalendarEventPlugin(AndroidCalendarComposerBackend(context))
        val compose = OpenComposePlugin(AndroidMessageComposerBackend(context))
        val registry = DefaultToolRegistry(listOf(
            ToolImplementationCandidate(calendar),
            ToolImplementationCandidate(compose),
        ))
        val snapshot = registry.snapshot(catalogContext())
        val executor = DefaultToolExecutor(registry)
        try {
            val composeResult = executor.execute(
                ModelToolCall(
                    "a7-compose-${UUID.randomUUID()}",
                    "open_compose",
                    buildJsonObject {
                        put("channel", "email")
                        put("to", "a7.surface@example.com")
                        put("subject", "A-7 production compose")
                        put("body", "This is a draft only. Do not send.")
                    },
                ),
                snapshot,
                toolContext("compose"),
            )
            assertOpened(composeResult, "email")
            val composeActivity = waitForLaunchedIntent(Intent.ACTION_SENDTO)
            assertTrue(composeActivity.contains("launchedFromPackage=com.example.hjp"))
            assertTrue(composeActivity.contains("android/com.android.internal.app.ResolverActivity"))
            assertTrue(composeActivity.contains("mailto:a7.surface%40example.com"))
            assertTrue(composeActivity.contains("subject=A-7%20production%20compose"))

            // The plugin returns as soon as the external surface opens; it never sends mail.
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)

            val calendarResult = executor.execute(
                ModelToolCall(
                    "a7-calendar-${UUID.randomUUID()}",
                    "create_calendar_event",
                    buildJsonObject {
                        put("title", "A-7 production calendar draft")
                        put("start_time", "2027-09-15T15:00")
                        put("attendee_emails", buildJsonArray { add(JsonPrimitive("a7.surface@example.com")) })
                    },
                ),
                snapshot,
                toolContext("calendar"),
            )
            assertOpened(calendarResult, "calendar")
            val calendarActivity = waitForLaunchedIntent(Intent.ACTION_INSERT)
            assertTrue(calendarActivity.contains("launchedFromPackage=com.example.hjp"))
            assertTrue(calendarActivity.contains("android/com.android.internal.app.ResolverActivity"))
            assertTrue(calendarActivity.contains(CalendarContract.Events.CONTENT_URI.toString()))
        } finally {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        }
    }

    @Test
    fun updatePolicyRequiresConfirmationAndAcceptedExecutionPersistsToProductionRoom() = runBlocking {
        val dao = HjpDatabase.getInstance(context).businessCardDao()
        val id = "A7-SURFACE-${UUID.randomUUID()}"
        val before = BusinessCardEntity(
            id = id, name = "A7 대상", nameEn = "A7 Target", company = "HJP", title = "QA",
            department = "Agent", industry = "software", location = "Seoul", phone = "010-0000-0000",
            mobile = "", email = "a7.surface@example.com", address = "", website = "",
            memo = "before-a7", tagsJson = "[]", updatedAt = "",
        )
        dao.insertAllAndReindex(listOf(before))
        try {
            val update = UpdateBusinessCardPlugin(RoomBusinessCardRepository(context, dao))
            val registry = DefaultToolRegistry(listOf(ToolImplementationCandidate(update)))
            val snapshot = registry.snapshot(catalogContext())
            val call = ModelToolCall(
                "a7-update-${UUID.randomUUID()}",
                "update_business_card",
                buildJsonObject {
                    put("card_id", id)
                    put("updates", buildJsonObject { put("memo", "after-a7-confirmed") })
                },
            )
            val policy = DefaultToolPolicyEngine().evaluate(
                ContactToolContracts.Update,
                call,
                AgentSessionView("a7-session", emptySet()),
            )
            assertTrue(policy is ToolPolicyDecision.RequireConfirmation)
            assertEquals("before-a7", requireNotNull(dao.getById(id)).memo)

            // Reject means AgentKernel releases the reservation and does not call the executor.
            assertEquals("before-a7", requireNotNull(dao.getById(id)).memo)

            val result = DefaultToolExecutor(registry).execute(call, snapshot, toolContext("update"))
            assertTrue(result is ToolExecutionResult.Success)
            assertEquals("after-a7-confirmed", requireNotNull(dao.getById(id)).memo)
        } finally {
            dao.deleteAndReindex(id)
            assertFalse(dao.getById(id) != null)
        }
    }

    private fun catalogContext() = CatalogContext(
        sessionId = "a7-surface",
        localeTag = "ko-KR",
        grantedPermissions = emptySet(),
        deviceCapabilities = setOf("android.external_ui", "contact.local_update"),
    )

    private fun toolContext(turnId: String) = ToolExecutionContext(
        sessionId = "a7-surface",
        turnId = turnId,
        localeTag = "ko-KR",
        deviceTimeZoneId = "Asia/Seoul",
    )

    private fun assertOpened(result: ToolExecutionResult, destination: String) {
        assertTrue("plugin failed: $result", result is ToolExecutionResult.Success)
        val data = (result as ToolExecutionResult.Success).data
        assertEquals("true", data["opened"]?.toString())
        assertEquals(destination, (data["destination"] as JsonPrimitive).content)
        assertEquals("true", data["requires_user_confirmation"]?.toString())
    }

    private fun waitForLaunchedIntent(action: String): String {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var activities = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            activities = activityDump()
            if (activities.contains("act=$action") && activities.contains("ResolverActivity")) {
                return activities
            }
            SystemClock.sleep(25L)
        }
        throw AssertionError("$action was not visible in Android Activity state: $activities")
    }

    private fun activityDump(): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities"),
        ).bufferedReader().use { it.readText() }

    private fun privateField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
