package com.hjp.desktop

import com.hjp.tool.android.GeneratedMessageDraft
import com.hjp.tool.android.MessageDraftGeneration
import com.hjp.tool.android.MessageDraftGenerator
import com.hjp.tool.android.MessageDraftRequest
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComposePipelineIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `direct email intent generates draft and executes open compose mock`() = runBlocking {
        val generator = FixedDraftGenerator(
            GeneratedMessageDraft(
                "미팅 감사드립니다",
                "안녕하세요. 지난 미팅에 귀한 시간을 내주셔서 감사합니다. 다시 연락드리겠습니다.",
            ),
        )
        val traces = mutableListOf<Pair<String, String>>()
        runner(realCards(), generator, traces).use { runner ->
            runner.validateData()
            val final = runner.runPrompt(
                "test@example.com한테 지난번 미팅에 대한 감사 내용을 담아서 메일 작성해줘.",
            )
            assertTrue(final.contains("mock"))
        }

        assertEquals("test@example.com", directRecipient(traces))
        val generated = trace(traces, "GENERATED_TOOL_CALL")
        assertTrue(generated.contains("\"channel\":\"email\""))
        assertTrue(generated.contains("\"to\":\"test@example.com\""))
        assertTrue(generated.contains("미팅 감사드립니다"))
        assertTrue(generated.contains("귀한 시간을"))
        assertTrue(trace(traces, "DRAFT_MODEL").contains("draft_source=fixture-gemma"))
        assertTrue(trace(traces, "GENERATED_TOOL_CALL").contains("application_orchestrator"))
        assertTrue(trace(traces, "TOOL_RESULT").contains("mock_success"))
    }

    @Test
    fun `named sms searches gets real phone generates draft and executes mock`() = runBlocking {
        val generator = FixedDraftGenerator(
            GeneratedMessageDraft(
                null,
                "안녕하세요, 김지원님. 지난 상담 감사드립니다. 다음 주에 다시 연락드리겠습니다.",
            ),
        )
        val traces = mutableListOf<Pair<String, String>>()
        runner(realCards(), generator, traces).use { runner ->
            runner.validateData()
            val final = runner.runPrompt(
                "김지원에게 지난 상담에 감사하고 다음 주에 다시 연락드리겠다는 문자를 작성해줘.",
            )
            assertTrue(final.contains("mock"))
        }

        val calls = traces.filter { it.first == "PARSED_TOOL_CALL" }.map { it.second }
        assertTrue(calls[0].contains("search_contacts"))
        assertTrue(calls[1].contains("get_contact"))
        assertTrue(calls[2].contains("open_compose"))
        val generated = trace(traces, "GENERATED_TOOL_CALL")
        assertTrue(generated.contains("\"channel\":\"sms\""))
        assertTrue(generated.contains("\"to\":\"010-0000-0001\""))
        assertTrue(generated.contains("다음 주"))
        assertEquals("김지원", generator.requests.single().recipientName)
    }

    @Test
    fun `explicit email and sms bodies are preserved without generator`() = runBlocking {
        val generator = FixedDraftGenerator(GeneratedMessageDraft("사용되면 안 됨", "변경됨"))
        val emailTrace = mutableListOf<Pair<String, String>>()
        runner(realCards(), generator, emailTrace).use { runner ->
            runner.validateData()
            runner.runPrompt("test@example.com에게 내용은 안녕하세요 라고 메일 작성해줘.")
        }
        assertTrue(trace(emailTrace, "GENERATED_TOOL_CALL").contains("\"body\":\"안녕하세요\""))

        val smsTrace = mutableListOf<Pair<String, String>>()
        runner(realCards(), generator, smsTrace).use { runner ->
            runner.validateData()
            runner.runPrompt("김지원에게 안녕하세요라고 문자 보내줘.")
        }
        assertTrue(trace(smsTrace, "GENERATED_TOOL_CALL").contains("\"body\":\"안녕하세요\""))
        assertTrue(trace(smsTrace, "GENERATED_TOOL_CALL").contains("010-0000-0001"))
        assertTrue(generator.requests.isEmpty())
    }

    @Test
    fun `nonexistent and ambiguous contacts never invent recipient`() = runBlocking {
        val generator = FixedDraftGenerator(GeneratedMessageDraft(null, "감사합니다."))
        val missingTrace = mutableListOf<Pair<String, String>>()
        val missing = runner(realCards(), generator, missingTrace).use { runner ->
            runner.validateData()
            runner.runPrompt("존재하지않는사람에게 지난 상담 감사 문자를 보내줘.")
        }
        assertTrue(missing.contains("찾지 못했습니다"))
        assertFalse(missingTrace.any { it.first == "GENERATED_TOOL_CALL" })

        val duplicateCards = fixtureCards(
            """[
              {"id":"D1","name":"동명이인","email":"one@example.com","phone":"010-1111-1111"},
              {"id":"D2","name":"동명이인","email":"two@example.com","phone":"010-2222-2222"}
            ]""",
        )
        val duplicateTrace = mutableListOf<Pair<String, String>>()
        val ambiguous = runner(duplicateCards, generator, duplicateTrace).use { runner ->
            runner.validateData()
            runner.runPrompt("동명이인에게 감사 메일 작성해줘.")
        }
        assertTrue(ambiguous.contains("하나로 특정"))
        assertFalse(duplicateTrace.any { it.first == "GENERATED_TOOL_CALL" })
    }

    @Test
    fun `missing email or phone in card stops before draft and compose`() = runBlocking {
        val generator = FixedDraftGenerator(GeneratedMessageDraft("감사", "감사합니다."))
        val noEmailCards = fixtureCards(
            """[{"id":"E1","name":"박가온","phone":"010-3333-3333"}]""",
        )
        val emailTrace = mutableListOf<Pair<String, String>>()
        val emailFinal = runner(noEmailCards, generator, emailTrace).use { runner ->
            runner.validateData()
            runner.runPrompt("박가온에게 지난 상담 감사 메일 작성해줘.")
        }
        assertTrue(emailFinal.contains("이메일 주소 정보가 없습니다"))
        assertFalse(emailTrace.any { it.first == "GENERATED_TOOL_CALL" })

        val noPhoneCards = fixtureCards(
            """[{"id":"P1","name":"최나래","email":"phone-missing@example.com"}]""",
        )
        val phoneTrace = mutableListOf<Pair<String, String>>()
        val phoneFinal = runner(noPhoneCards, generator, phoneTrace).use { runner ->
            runner.validateData()
            runner.runPrompt("최나래에게 지난 상담 감사 문자를 작성해줘.")
        }
        assertTrue(phoneFinal.contains("전화번호 정보가 없습니다"))
        assertFalse(phoneTrace.any { it.first == "GENERATED_TOOL_CALL" })
        assertTrue(generator.requests.isEmpty())
    }

    private fun runner(
        cards: File,
        generator: MessageDraftGenerator,
        traces: MutableList<Pair<String, String>>,
    ) = DesktopAgentRunner(
        config = config(cards),
        traceObserver = { stage, message -> traces += stage to message },
        printOutput = false,
        messageDraftGeneratorOverride = generator,
    )

    private fun config(cards: File): DesktopConfig {
        val root = projectRoot()
        return DesktopConfig(
            mode = RunnerMode.FULL,
            prompt = null,
            debug = false,
            liteRtBin = File(root, "missing-bin"),
            modelId = "gemma3-1b-it-int4",
            modelFile = File(root, "missing-model"),
            backend = "cpu",
            cardDataFile = cards,
            timeoutMillis = 2_000,
            projectRoot = root,
        )
    }

    private fun fixtureCards(json: String): File =
        temporaryFolder.newFile("cards-${System.nanoTime()}.json").apply { writeText(json) }

    private fun realCards(): File =
        File(projectRoot(), "app/src/main/assets/cards/business_cards.json")

    private fun projectRoot(): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        error("project root not found")
    }

    private fun trace(traces: List<Pair<String, String>>, stage: String): String =
        traces.lastOrNull { it.first == stage }?.second.orEmpty()

    private fun directRecipient(traces: List<Pair<String, String>>): String =
        Regex("""recipient=([^\s]+)""")
            .find(trace(traces, "RECIPIENT_RESOLUTION"))
            ?.groupValues?.get(1).orEmpty()
}

private class FixedDraftGenerator(
    private val draft: GeneratedMessageDraft,
) : MessageDraftGenerator {
    val requests = mutableListOf<MessageDraftRequest>()

    override suspend fun generate(request: MessageDraftRequest): MessageDraftGeneration {
        requests += request
        return MessageDraftGeneration(
            draft = draft,
            source = "fixture-gemma",
            rawOutput = """{"body":"fixture"}""",
            jsonParsed = true,
            usedFallback = false,
            validationReason = "valid",
        )
    }
}
