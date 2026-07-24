package com.hjp.tool.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object MessageDraftPrompt {
    fun build(request: MessageDraftRequest): String {
        val format = if (request.channel == MessageChannel.EMAIL) {
            """{"subject":"제목","body":"본문"}"""
        } else {
            """{"body":"본문"}"""
        }
        val sentenceRule = if (request.channel == MessageChannel.EMAIL) "2~5문장" else "1~3문장"
        return """
            당신은 한국어 메시지 초안 작성기입니다. 도구를 호출하지 마세요.
            반드시 JSON object 하나만 출력하세요. Markdown code fence와 설명을 출력하지 마세요.
            출력 형식: $format
            본문은 $sentenceRule, 자연스럽고 정중하게 작성하세요.
            사용자가 말하지 않은 날짜, 시간, 회사명, 미팅 세부내용, 약속, 전화번호, 이메일을 만들지 마세요.
            "지난번 미팅"이나 "지난 상담"의 상세 내용이 없으면 일반적인 감사 표현만 사용하세요.
            수신자 이름이 있으면 자연스러운 호칭에만 사용할 수 있습니다.

            channel=${request.channel.name.lowercase()}
            recipient_name=${request.recipientName.orEmpty()}
            purpose=${request.purpose}
            user_context=${request.userContext}
            requested_tone=${request.requestedTone.orEmpty()}
        """.trimIndent()
    }
}

object SafeMessageDraftGenerator {
    private val json = Json { ignoreUnknownKeys = true }
    private val emailOrPhone = Regex(
        """(?:[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|(?:\+?\d{1,3}[-\s]?)?0\d{1,2}[-\s]?\d{3,4}[-\s]?\d{4})""",
    )
    private val concreteDate = Regex("""\b\d{4}[-./년]\s*\d{1,2}(?:[-./월]\s*\d{1,2})?""")
    private val deliveryClaims = Regex(
        """(발송|전송|보냈|보내\s*드렸|메일\s*발송|문자\s*발송)(?:했|해|되었|드립|완료)""",
    )
    private val unsupportedDetailTerms = listOf(
        "팀원", "협력", "프로젝트", "계약", "제안서", "견적", "결정사항",
        "논의사항", "회의록", "성과", "파트너십", "회사", "부서",
    )

    fun fromModelOutput(
        request: MessageDraftRequest,
        rawOutput: String,
        source: String = "litert-model",
    ): MessageDraftGeneration {
        val parsed = parseJson(rawOutput)
        if (parsed == null) return fallback(request, source, rawOutput, "json_parse_failed")
        val subject = (parsed["subject"] as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)
        val body = (parsed["body"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val reason = validate(request, subject, body)
        if (reason != null) return fallback(request, source, rawOutput, reason, jsonParsed = true)
        return MessageDraftGeneration(
            draft = GeneratedMessageDraft(subject = subject, body = body),
            source = source,
            rawOutput = rawOutput,
            jsonParsed = true,
            usedFallback = false,
            validationReason = "valid",
        )
    }

    fun fallback(
        request: MessageDraftRequest,
        source: String = "safe_template",
        rawOutput: String? = null,
        reason: String = "template_requested",
        jsonParsed: Boolean = false,
    ): MessageDraftGeneration = MessageDraftGeneration(
        draft = template(request),
        source = source,
        rawOutput = rawOutput,
        jsonParsed = jsonParsed,
        usedFallback = true,
        validationReason = reason,
    )

    fun explicit(
        channel: MessageChannel,
        subject: String?,
        body: String,
    ): MessageDraftGeneration = MessageDraftGeneration(
        draft = GeneratedMessageDraft(
            subject = if (channel == MessageChannel.EMAIL) {
                subject?.takeIf(String::isNotBlank) ?: "메시지 드립니다"
            } else null,
            body = body,
        ),
        source = "user_provided",
        jsonParsed = true,
        usedFallback = false,
        validationReason = "user_body_preserved",
    )

    private fun parseJson(rawOutput: String): JsonObject? {
        val clean = rawOutput.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val candidate = clean.substringAfter('{', "")
            .takeIf(String::isNotEmpty)
            ?.let { "{$it" }
            ?.substringBeforeLast('}', "")
            ?.takeIf(String::isNotEmpty)
            ?.let { "$it}" }
            ?: return null
        return runCatching { json.parseToJsonElement(candidate) as? JsonObject }.getOrNull()
    }

    private fun validate(
        request: MessageDraftRequest,
        subject: String?,
        body: String,
    ): String? {
        if (body.isBlank()) return "empty_body"
        if (request.channel == MessageChannel.EMAIL && subject.isNullOrBlank()) return "empty_subject"
        if ((subject?.length ?: 0) > 100) return "subject_too_long"
        val maxLength = if (request.channel == MessageChannel.EMAIL) 1_200 else 300
        if (body.length > maxLength) return "body_too_long"
        if (Regex("""\s{2,}""").containsMatchIn(body)) return "malformed_spacing"
        if (deliveryClaims.containsMatchIn(body)) return "unsupported_delivery_claim"
        val sentences = body.split(Regex("""(?<=[.!?])\s+|\n+"""))
            .map(String::trim).filter(String::isNotEmpty)
        val expected = if (request.channel == MessageChannel.EMAIL) 2..5 else 1..3
        if (sentences.size !in expected) return "invalid_sentence_count"
        if (sentences.distinct().size != sentences.size) return "repeated_sentences"
        if (hasRepeatedChunk(body)) return "repeated_output"
        val allowedContext = request.userContext + " " + request.purpose
        unsupportedDetailTerms.firstOrNull { it in body && it !in allowedContext }
            ?.let { return "unsupported_detail:$it" }
        if (emailOrPhone.findAll(body).any { it.value !in allowedContext }) return "invented_contact"
        if (concreteDate.findAll(body).any { it.value !in allowedContext }) return "invented_date"
        return null
    }

    private fun hasRepeatedChunk(body: String): Boolean {
        val normalized = body.replace(Regex("\\s+"), " ").trim()
        if (normalized.length < 40) return false
        for (size in 12..minOf(80, normalized.length / 2)) {
            val seen = HashSet<String>()
            for (start in 0..normalized.length - size step maxOf(1, size / 2)) {
                val chunk = normalized.substring(start, start + size)
                if (!seen.add(chunk)) return true
            }
        }
        return false
    }

    private fun template(request: MessageDraftRequest): GeneratedMessageDraft {
        val context = (request.purpose + " " + request.userContext).lowercase()
        val greeting = request.recipientName
            ?.trim()?.takeIf(String::isNotEmpty)
            ?.let { "안녕하세요, ${it}님." }
            ?: "안녕하세요."
        val thanks = when {
            "상담" in context && "감사" in context -> "지난 상담에 감사드립니다."
            ("미팅" in context || "회의" in context) && "감사" in context ->
                "지난 미팅에서 귀한 시간을 내주셔서 감사합니다."
            ("미팅" in context || "회의" in context) && "가능" in context ->
                "회의 가능 여부를 문의드립니다."
            "데모" in context && ("일정" in context || "문의" in context) ->
                "데모 일정을 문의드립니다."
            "파트너십" in context && "제안" in context ->
                "파트너십을 제안드리고자 연락드립니다."
            "감사" in context -> "지난번 도움에 감사드립니다."
            else -> "연락드리고자 메시지를 남깁니다."
        }
        val followUp = when {
            "다음 주" in context || "다음주" in context -> "다음 주에 다시 연락드리겠습니다."
            "연락" in context -> "다시 연락드리겠습니다."
            "감사" in context -> "다시 한번 감사드립니다."
            else -> "확인 부탁드립니다."
        }
        return if (request.channel == MessageChannel.EMAIL) {
            val subject = when {
                ("미팅" in context || "회의" in context) && "감사" in context -> "미팅 감사드립니다"
                "상담" in context && "감사" in context -> "상담 감사드립니다"
                ("미팅" in context || "회의" in context) && "가능" in context -> "회의 가능 여부 문의"
                "데모" in context && ("일정" in context || "문의" in context) -> "데모 일정 문의"
                "파트너십" in context && "제안" in context -> "파트너십 제안"
                "감사" in context -> "감사드립니다"
                else -> "메시지 드립니다"
            }
            GeneratedMessageDraft(subject, listOf(greeting, thanks, followUp).distinct().joinToString(" "))
        } else {
            GeneratedMessageDraft(null, listOf(greeting, thanks, followUp).distinct().joinToString(" "))
        }
    }
}

class TemplateMessageDraftGenerator : MessageDraftGenerator {
    override suspend fun generate(request: MessageDraftRequest): MessageDraftGeneration =
        SafeMessageDraftGenerator.fallback(request)
}
