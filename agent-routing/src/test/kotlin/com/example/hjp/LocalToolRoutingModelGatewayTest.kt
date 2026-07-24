package com.example.hjp

import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelInput
import com.hjp.agent.contract.ModelSessionConfig
import com.hjp.agent.contract.ModelToolResponse
import com.hjp.tool.android.AndroidIntentToolContracts
import com.hjp.tool.android.GeneratedMessageDraft
import com.hjp.tool.android.MessageDraftGeneration
import com.hjp.tool.android.MessageDraftGenerator
import com.hjp.tool.android.MessageDraftRequest
import com.hjp.tool.contact.ContactToolContracts
import com.hjp.tool.contract.ToolContract
import com.hjp.tool.contract.ToolCatalogSnapshot
import com.hjp.tool.datetime.DateTimeToolContracts
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolRoutingModelGatewayTest {
    @Test
    fun `contact request produces search tool call and consumes its result`() = runBlocking {
        val session = session(ContactToolContracts.Search)

        val decision = session.decide(ModelInput.User("김민수 명함 찾아줘.")) as ModelDecision.ToolCalls
        val call = decision.calls.single()
        assertEquals("search_contacts", call.modelToolName)
        assertEquals("김민수", (call.arguments["query"] as JsonPrimitive).content)

        val final = session.continueWithToolResult(ModelToolResponse(
            call.callId,
            call.modelToolName,
            buildJsonObject {
                put("ok", true)
                put("data", buildJsonObject {
                    put("results", buildJsonArray { })
                    put("count", 0)
                })
            },
        )) as ModelDecision.FinalCandidate
        assertEquals("‘김민수’에 해당하는 명함을 찾지 못했습니다.", final.draftText)
    }

    @Test
    fun `contact detail request searches then gets the selected real record`() = runBlocking {
        val session = session(ContactToolContracts.Search, ContactToolContracts.Get)

        val search = session.decide(ModelInput.User("김지원 연락처 보여줘.")) as ModelDecision.ToolCalls
        assertEquals("search_contacts", search.calls.single().modelToolName)
        val get = session.continueWithToolResult(searchSuccess(search.calls.single().callId, "search_contacts"))
            as ModelDecision.ToolCalls
        assertEquals("get_contact", get.calls.single().modelToolName)
        val final = session.continueWithToolResult(contactSuccess(get.calls.single().callId, "get_contact"))
            as ModelDecision.FinalCandidate

        assertTrue(final.draftText.contains("jiwon@example.com"))
        assertTrue(final.draftText.contains("010-0000-0001"))
    }

    @Test
    fun `calendar request produces create calendar event tool call`() = runBlocking {
        val session = session(AndroidIntentToolContracts.Calendar)

        val decision = session.decide(ModelInput.User("2026년 7월 10일 오후 2시 회의 일정 만들어줘.")) as ModelDecision.ToolCalls
        val call = decision.calls.single()

        assertEquals("create_calendar_event", call.modelToolName)
        assertEquals("회의", (call.arguments["title"] as JsonPrimitive).content)
        assertEquals("2026-07-10T14:00", (call.arguments["start_time"] as JsonPrimitive).content)

        val final = session.continueWithToolResult(externalUiSuccess(call.callId, call.modelToolName, "calendar"))
            as ModelDecision.FinalCandidate
        assertEquals("캘린더 작성 화면을 열었습니다. 저장 전에 확인해 주세요.", final.draftText)
    }

    @Test
    fun `relative calendar request checks current date before calendar tool call`() = runBlocking {
        val session = session(DateTimeToolContracts.Current, AndroidIntentToolContracts.Calendar)

        val dateDecision = session.decide(ModelInput.User("내일 오후 2시 회의 일정 만들어줘."))
            as ModelDecision.ToolCalls
        val dateCall = dateDecision.calls.single()
        assertEquals("get_current_datetime", dateCall.modelToolName)

        val calendarDecision = session.continueWithToolResult(currentDateSuccess(dateCall.callId, dateCall.modelToolName))
            as ModelDecision.ToolCalls
        val calendarCall = calendarDecision.calls.single()

        assertEquals("create_calendar_event", calendarCall.modelToolName)
        assertEquals("회의", (calendarCall.arguments["title"] as JsonPrimitive).content)
        assertEquals("2026-07-11T14:00", (calendarCall.arguments["start_time"] as JsonPrimitive).content)
    }

    @Test
    fun `current datetime request produces current datetime tool call and final text`() = runBlocking {
        val session = session(DateTimeToolContracts.Current)

        val decision = session.decide(ModelInput.User("현재 시간 알려줘."))
            as ModelDecision.ToolCalls
        val call = decision.calls.single()
        assertEquals("get_current_datetime", call.modelToolName)

        val final = session.continueWithToolResult(currentDateSuccess(call.callId, call.modelToolName))
            as ModelDecision.FinalCandidate
        assertTrue(final.draftText.contains("2026-07-10"))
        assertTrue(final.draftText.contains("Asia/Seoul"))
    }

    @Test
    fun `email request produces compose tool call`() = runBlocking {
        val session = session(AndroidIntentToolContracts.Compose)

        val decision = session.decide(
            ModelInput.User("jiwon@example.com에게 제목은 회의 요청, 내용은 내일 가능하신가요 라고 메일 작성해줘."),
        ) as ModelDecision.ToolCalls
        val call = decision.calls.single()

        assertEquals("open_compose", call.modelToolName)
        assertEquals("email", (call.arguments["channel"] as JsonPrimitive).content)
        assertEquals("jiwon@example.com", (call.arguments["to"] as JsonPrimitive).content)
        assertEquals("회의 요청", (call.arguments["subject"] as JsonPrimitive).content)
        assertEquals("내일 가능하신가요", (call.arguments["body"] as JsonPrimitive).content)
    }

    @Test
    fun `email request without body or purpose asks once instead of opening empty compose`() = runBlocking {
        val session = session(AndroidIntentToolContracts.Compose)

        val decision = session.decide(ModelInput.User("test@example.com에게 메일 작성해줘."))
            as ModelDecision.FinalCandidate

        assertTrue(decision.draftText.contains("내용이나 목적"))
    }

    @Test
    fun `sms request produces compose tool call`() = runBlocking {
        val session = session(AndroidIntentToolContracts.Compose)

        val decision = session.decide(ModelInput.User("010-0000-0001로 회의에 늦는다고 문자 보내줘."))
            as ModelDecision.ToolCalls
        val call = decision.calls.single()

        assertEquals("open_compose", call.modelToolName)
        assertEquals("sms", (call.arguments["channel"] as JsonPrimitive).content)
        assertEquals("010-0000-0001", (call.arguments["to"] as JsonPrimitive).content)
        assertEquals("회의에 늦는다고", (call.arguments["body"] as JsonPrimitive).content)
    }

    @Test
    fun `contact email request searches gets contact and opens compose`() = runBlocking {
        val session = session(
            ContactToolContracts.Search,
            ContactToolContracts.Get,
            AndroidIntentToolContracts.Compose,
        )

        val searchDecision = session.decide(ModelInput.User("김지원에게 내용은 안녕하세요 라고 메일 작성해줘."))
            as ModelDecision.ToolCalls
        val searchCall = searchDecision.calls.single()
        assertEquals("search_contacts", searchCall.modelToolName)
        assertEquals("김지원", (searchCall.arguments["query"] as JsonPrimitive).content)

        val getDecision = session.continueWithToolResult(searchSuccess(searchCall.callId, searchCall.modelToolName))
            as ModelDecision.ToolCalls
        val getCall = getDecision.calls.single()
        assertEquals("get_contact", getCall.modelToolName)
        assertEquals("C001", (getCall.arguments["card_id"] as JsonPrimitive).content)
        assertEquals("email", (getCall.arguments["purpose"] as JsonPrimitive).content)

        val composeDecision = session.continueWithToolResult(contactSuccess(getCall.callId, getCall.modelToolName))
            as ModelDecision.ToolCalls
        val composeCall = composeDecision.calls.single()
        assertEquals("open_compose", composeCall.modelToolName)
        assertEquals("email", (composeCall.arguments["channel"] as JsonPrimitive).content)
        assertEquals("jiwon@example.com", (composeCall.arguments["to"] as JsonPrimitive).content)
        assertEquals("안녕하세요", (composeCall.arguments["body"] as JsonPrimitive).content)
    }

    @Test
    fun `direct email intent uses generated subject and body`() = runBlocking {
        val generator = RecordingDraftGenerator(MessageDraftGeneration(
            GeneratedMessageDraft("미팅 감사드립니다", "안녕하세요. 지난 미팅에 감사드립니다. 다시 연락드리겠습니다."),
            "fixture-gemma",
            """{"subject":"미팅 감사드립니다","body":"안녕하세요. 지난 미팅에 감사드립니다. 다시 연락드리겠습니다."}""",
            jsonParsed = true,
            usedFallback = false,
            validationReason = "valid",
        ))
        val session = sessionWithGenerator(generator, AndroidIntentToolContracts.Compose)

        val call = (session.decide(ModelInput.User(
            "test@example.com한테 지난번 미팅에 대한 감사 내용을 담아서 메일 작성해줘.",
        )) as ModelDecision.ToolCalls).calls.single()

        assertEquals("open_compose", call.modelToolName)
        assertEquals("test@example.com", (call.arguments["to"] as JsonPrimitive).content)
        assertEquals("미팅 감사드립니다", (call.arguments["subject"] as JsonPrimitive).content)
        assertTrue((call.arguments["body"] as JsonPrimitive).content.contains("감사"))
        assertEquals(1, generator.requests.size)
        assertTrue(generator.requests.single().purpose.contains("지난번 미팅"))
    }

    @Test
    fun `user supplied subject overrides generated subject while body is generated`() = runBlocking {
        val generator = RecordingDraftGenerator(MessageDraftGeneration(
            GeneratedMessageDraft("모델 제목", "안녕하세요. 데모를 요청드립니다. 확인 부탁드립니다."),
            "fixture-gemma",
            """{"subject":"모델 제목","body":"안녕하세요. 데모를 요청드립니다. 확인 부탁드립니다."}""",
            jsonParsed = true,
            usedFallback = false,
            validationReason = "valid",
        ))
        val session = sessionWithGenerator(generator, AndroidIntentToolContracts.Compose)

        val call = (session.decide(ModelInput.User(
            "ai@example.com으로 제목이 데모 요청인 이메일을 작성해 줘.",
        )) as ModelDecision.ToolCalls).calls.single()

        assertEquals("데모 요청", (call.arguments["subject"] as JsonPrimitive).content)
        assertEquals("안녕하세요. 데모를 요청드립니다. 확인 부탁드립니다.", (call.arguments["body"] as JsonPrimitive).content)
    }

    @Test
    fun `company recipient ending in ro is not truncated inside its name`() = runBlocking {
        val session = session(
            ContactToolContracts.Search,
            ContactToolContracts.Get,
            AndroidIntentToolContracts.Compose,
        )

        val search = session.decide(ModelInput.User(
            "비전글로벌 대표에게 파트너십 제안 메일 초안을 준비해 줘.",
        )) as ModelDecision.ToolCalls

        assertEquals("비전글로벌 대표", (search.calls.single().arguments["query"] as JsonPrimitive).content)
    }

    @Test
    fun `draft request includes only a relevant bounded previous conversation`() = runBlocking {
        val generator = RecordingDraftGenerator(MessageDraftGeneration(
            GeneratedMessageDraft("미팅 감사", "안녕하세요. 지난 미팅에 감사드립니다. 확인 부탁드립니다."),
            "fixture-gemma",
            """{"subject":"미팅 감사","body":"안녕하세요. 지난 미팅에 감사드립니다. 확인 부탁드립니다."}""",
            jsonParsed = true,
            usedFallback = false,
            validationReason = "valid",
        ))
        val session = sessionWithGenerator(generator, AndroidIntentToolContracts.Compose)

        session.decide(ModelInput.User("지난 미팅에서는 제품 데모를 논의했어."))
        session.decide(ModelInput.User(
            "test@example.com에게 지난 미팅 감사 메일을 작성해줘.",
        ))

        val context = generator.requests.single().userContext
        assertTrue(context.contains("제품 데모"))
        assertTrue(context.contains("현재 요청"))
    }

    @Test
    fun `named sms resolves contact then generates body`() = runBlocking {
        val generator = RecordingDraftGenerator(MessageDraftGeneration(
            GeneratedMessageDraft(null, "안녕하세요, 김지원님. 지난 상담 감사드립니다. 다음 주에 다시 연락드리겠습니다."),
            "fixture-gemma",
            """{"body":"안녕하세요, 김지원님. 지난 상담 감사드립니다. 다음 주에 다시 연락드리겠습니다."}""",
            jsonParsed = true,
            usedFallback = false,
            validationReason = "valid",
        ))
        val session = sessionWithGenerator(
            generator,
            ContactToolContracts.Search,
            ContactToolContracts.Get,
            AndroidIntentToolContracts.Compose,
        )

        val search = session.decide(ModelInput.User(
            "김지원에게 지난 상담에 감사하고 다음 주에 다시 연락드리겠다는 문자를 작성해줘.",
        )) as ModelDecision.ToolCalls
        val get = session.continueWithToolResult(
            searchSuccess(search.calls.single().callId, "search_contacts"),
        ) as ModelDecision.ToolCalls
        val compose = session.continueWithToolResult(
            contactSuccess(get.calls.single().callId, "get_contact"),
        ) as ModelDecision.ToolCalls
        val call = compose.calls.single()

        assertEquals("open_compose", call.modelToolName)
        assertEquals("sms", (call.arguments["channel"] as JsonPrimitive).content)
        assertEquals("010-0000-0001", (call.arguments["to"] as JsonPrimitive).content)
        assertTrue((call.arguments["body"] as JsonPrimitive).content.contains("다음 주"))
        assertEquals("김지원", generator.requests.single().recipientName)
    }

    @Test
    fun `contact update request searches gets contact and updates business card`() = runBlocking {
        val session = session(
            ContactToolContracts.Search,
            ContactToolContracts.Get,
            ContactToolContracts.Update,
        )

        val searchDecision = session.decide(ModelInput.User("김지원 명함 메모를 VIP로 수정해줘."))
            as ModelDecision.ToolCalls
        val searchCall = searchDecision.calls.single()
        assertEquals("search_contacts", searchCall.modelToolName)
        assertEquals("김지원", (searchCall.arguments["query"] as JsonPrimitive).content)

        val getDecision = session.continueWithToolResult(searchSuccess(searchCall.callId, searchCall.modelToolName))
            as ModelDecision.ToolCalls
        val getCall = getDecision.calls.single()
        assertEquals("get_contact", getCall.modelToolName)
        assertEquals("C001", (getCall.arguments["card_id"] as JsonPrimitive).content)

        val updateDecision = session.continueWithToolResult(contactSuccess(getCall.callId, getCall.modelToolName))
            as ModelDecision.ToolCalls
        val updateCall = updateDecision.calls.single()
        assertEquals("update_business_card", updateCall.modelToolName)
        assertEquals("C001", (updateCall.arguments["card_id"] as JsonPrimitive).content)
        val updates = updateCall.arguments["updates"] as JsonObject
        assertEquals("VIP", (updates["memo"] as JsonPrimitive).content)

        val final = session.continueWithToolResult(updateSuccess(updateCall.callId, updateCall.modelToolName))
            as ModelDecision.FinalCandidate
        assertEquals("명함을 수정했습니다.", final.draftText)
    }

    private suspend fun session(vararg contracts: ToolContract) =
        LocalToolRoutingModelGateway().openSession(ModelSessionConfig(
            systemInstruction = "test",
            toolCatalog = ToolCatalogSnapshot(
                revision = "r1",
                bindingRevision = "b1",
                createdAtEpochMillis = 0,
                bindings = emptyList(),
                contractsByModelName = contracts.associateBy { it.modelName },
            ),
            localeTag = "ko-KR",
        ))

    private suspend fun sessionWithGenerator(
        generator: MessageDraftGenerator,
        vararg contracts: ToolContract,
    ) = LocalToolRoutingModelGateway(draftGenerator = generator).openSession(ModelSessionConfig(
        systemInstruction = "test",
        toolCatalog = ToolCatalogSnapshot(
            revision = "r1",
            bindingRevision = "b1",
            createdAtEpochMillis = 0,
            bindings = emptyList(),
            contractsByModelName = contracts.associateBy { it.modelName },
        ),
        localeTag = "ko-KR",
    ))

    private fun externalUiSuccess(callId: String, toolName: String, destination: String) = ModelToolResponse(
        callId,
        toolName,
        buildJsonObject {
            put("ok", true)
            put("data", buildJsonObject {
                put("opened", true)
                put("destination", destination)
                put("requires_user_confirmation", true)
            })
        },
    )

    private fun searchSuccess(callId: String, toolName: String) = ModelToolResponse(
        callId,
        toolName,
        buildJsonObject {
            put("ok", true)
            put("data", buildJsonObject {
                put("results", buildJsonArray {
                    add(buildJsonObject {
                        put("card_id", "C001")
                        put("name", "김지원")
                        put("company", "비전글로벌")
                        put("title", "대표이사")
                        put("location", "서울")
                    })
                })
                put("count", 1)
            })
        },
    )

    private fun currentDateSuccess(callId: String, toolName: String) = ModelToolResponse(
        callId,
        toolName,
        buildJsonObject {
            put("ok", true)
            put("data", buildJsonObject {
                put("date", "2026-07-10")
                put("time", "10:30:00")
                put("datetime", "2026-07-10T10:30:00+09:00")
                put("timezone", "Asia/Seoul")
                put("epoch_millis", 1_783_646_600_000L)
                put("utc_offset", "+09:00")
            })
        },
    )

    private fun contactSuccess(callId: String, toolName: String) = ModelToolResponse(
        callId,
        toolName,
        buildJsonObject {
            put("ok", true)
            put("data", buildJsonObject {
                put("card_id", "C001")
                put("name", "김지원")
                put("company", "비전글로벌")
                put("title", "대표이사")
                put("phone", "010-0000-0001")
                put("email", "jiwon@example.com")
            })
        },
    )

    private fun updateSuccess(callId: String, toolName: String) = ModelToolResponse(
        callId,
        toolName,
        buildJsonObject {
            put("ok", true)
            put("data", buildJsonObject {
                put("before", buildJsonObject {
                    put("card_id", "C001")
                    put("name", "김지원")
                    put("memo", "")
                })
                put("after", buildJsonObject {
                    put("card_id", "C001")
                    put("name", "김지원")
                    put("memo", "VIP")
                })
            })
        },
    )
}

private class RecordingDraftGenerator(
    private val result: MessageDraftGeneration,
) : MessageDraftGenerator {
    val requests = mutableListOf<MessageDraftRequest>()

    override suspend fun generate(request: MessageDraftRequest): MessageDraftGeneration {
        requests += request
        return result
    }
}
