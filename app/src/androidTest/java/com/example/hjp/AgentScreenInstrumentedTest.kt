package com.example.hjp

import android.os.Build
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import com.hjp.tool.android.AndroidCalendarComposerBackend
import com.hjp.tool.android.AndroidMessageComposerBackend
import com.hjp.tool.android.MessageChannel
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class AgentScreenInstrumentedTest {
    private val modelFileRule = ReadableModelFileRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(modelFileRule).around(composeRule)

    @Test
    fun launchShowsAgentTitle() {
        composeRule.onNodeWithText("HJP Agent").assertIsDisplayed()
        composeRule.onNodeWithText("온디바이스 Single ReAct · 검색/조회/외부 작성 연동").assertIsDisplayed()
    }

    @Test
    fun readableModelEnablesInputAndTypingEnablesSend() {
        val input = composeRule.onNode(hasSetTextAction())
        val send = composeRule.onNodeWithText("보내기")

        input.assertIsEnabled()
        send.assertIsNotEnabled()

        input.performTextInput("판교 AI 개발자 찾아줘")

        input.assertTextContains("판교 AI 개발자 찾아줘")
        send.assertIsEnabled()
    }

    @Test
    fun activityRecreationPreservesDraft() {
        val draft = "이 초안은 회전 후에도 남아야 해"
        composeRule.onNode(hasSetTextAction()).performTextInput(draft)

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNode(hasSetTextAction())
            .assertIsEnabled()
            .assertTextContains(draft)
        composeRule.onNodeWithText("보내기").assertIsEnabled()
    }

    @Test
    fun newConversationClearsDraftAndResetsUiSession() {
        val input = composeRule.onNode(hasSetTextAction())
        input.performTextInput("clear this draft")

        composeRule.onNodeWithText("새 대화").performClick()
        composeRule.waitForIdle()

        input.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        composeRule.onNodeWithText("새 대화를 시작했습니다.").assertIsDisplayed()
        composeRule.onNodeWithText("보내기").assertIsNotEnabled()
    }

    @Test
    fun calendarEmailAndSmsIntentsResolveForAppUid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val calendar = AndroidCalendarComposerBackend(context)
        val messages = AndroidMessageComposerBackend(context)

        assertTrue("Calendar INSERT intent has no resolver", calendar.isAvailable())
        assertTrue("Email SENDTO intent has no resolver", messages.isAvailable(MessageChannel.EMAIL))
        assertTrue("SMS SENDTO intent has no resolver", messages.isAvailable(MessageChannel.SMS))
    }

    @Test
    fun businessCardPromptCallsContactSearchWithoutCrashing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelRoot = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val model = File(modelRoot, "hjp-agent.litertlm")
        val isEmulator = Build.HARDWARE.equals("ranchu", ignoreCase = true) ||
            Build.HARDWARE.equals("goldfish", ignoreCase = true)
        assumeTrue(
            "A real model or the emulator compatibility router is required",
            isEmulator || model.length() > 1_000_000,
        )

        composeRule.onNode(hasSetTextAction()).performTextInput("김민수 명함 찾아줘.")
        composeRule.onNodeWithText("보내기").performClick()

        composeRule.waitUntil(timeoutMillis = 120_000) {
            composeRule.onAllNodes(hasText("‘김민수’에 해당하는 명함을 찾지 못했습니다."))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 120_000) {
            composeRule.onAllNodes(hasSetTextAction() and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).assertIsEnabled()
    }

    @Test
    fun knownBusinessCardPromptReturnsRyeongSearchResult() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isEmulator = Build.HARDWARE.equals("ranchu", ignoreCase = true) ||
            Build.HARDWARE.equals("goldfish", ignoreCase = true)
        assumeTrue("The emulator compatibility router is required", isEmulator)
        // This asserts the *content* of a retrieval result, so it belongs to variants that ship the
        // retrieval models. The model-free lifecycle variant deliberately has none, and a skip there
        // is honest; silently passing it would claim search coverage the variant cannot provide.
        assumeTrue(
            "Retrieval models are not packaged in this variant",
            context.assets.list("models").orEmpty().isNotEmpty(),
        )

        composeRule.onNode(hasSetTextAction()).performTextInput("김지원 명함 찾아줘.")
        composeRule.onNodeWithText("보내기").performClick()

        composeRule.waitUntil(timeoutMillis = 120_000) {
            composeRule.onAllNodes(
                hasText("명함 검색 결과입니다.\n- 김지원 · 비전글로벌 · 대표이사 · 서울"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).assertIsEnabled()
    }
}

/**
 * Makes the app's existing readiness contract true before MainActivity launches.
 * The test never opens the file, so a small sentinel is sufficient and native
 * LiteRT initialization is never reached.
 */
private class ReadableModelFileRule : ExternalResource() {
    private lateinit var modelFile: File
    private var createdByTest = false

    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelRoot = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        check(modelRoot.exists() || modelRoot.mkdirs()) { "Could not create model test directory" }
        modelFile = File(modelRoot, "hjp-agent.litertlm")
        if (!modelFile.exists()) {
            check(modelFile.createNewFile()) { "Could not create model test sentinel" }
            createdByTest = true
        }
        check(modelFile.isFile && modelFile.canRead()) { "Model test sentinel is not readable" }
    }

    override fun after() {
        if (createdByTest) modelFile.delete()
    }
}
