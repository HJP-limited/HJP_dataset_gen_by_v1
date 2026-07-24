package com.hjp.tool.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeMessageDraftGeneratorTest {
    @Test
    fun `valid email json is accepted`() {
        val result = SafeMessageDraftGenerator.fromModelOutput(
            request(MessageChannel.EMAIL, "지난 미팅 감사"),
            """{"subject":"미팅 감사드립니다","body":"안녕하세요. 지난 미팅에 감사드립니다. 다시 연락드리겠습니다."}""",
        )

        assertTrue(result.jsonParsed)
        assertFalse(result.usedFallback)
        assertEquals("미팅 감사드립니다", result.draft.subject)
    }

    @Test
    fun `json parsing failure uses safe template`() {
        val result = SafeMessageDraftGenerator.fromModelOutput(
            request(MessageChannel.EMAIL, "지난 미팅 감사"),
            "제목: 미팅 감사",
        )

        assertTrue(result.usedFallback)
        assertFalse(result.jsonParsed)
        assertEquals("json_parse_failed", result.validationReason)
        assertTrue(result.draft.body.contains("감사"))
    }

    @Test
    fun `empty and repeated model bodies use fallback`() {
        val empty = SafeMessageDraftGenerator.fromModelOutput(
            request(MessageChannel.SMS, "지난 상담 감사"),
            """{"body":""}""",
        )
        val repeated = SafeMessageDraftGenerator.fromModelOutput(
            request(MessageChannel.SMS, "지난 상담 감사"),
            """{"body":"감사드립니다. 감사드립니다. 감사드립니다. 감사드립니다."}""",
        )

        assertTrue(empty.usedFallback)
        assertTrue(empty.jsonParsed)
        assertEquals("empty_body", empty.validationReason)
        assertTrue(repeated.usedFallback)
        assertTrue(repeated.jsonParsed)
        assertTrue(repeated.validationReason.startsWith("invalid_sentence_count") ||
            repeated.validationReason.startsWith("repeated"))
    }

    @Test
    fun `explicit body is preserved verbatim`() {
        val result = SafeMessageDraftGenerator.explicit(
            MessageChannel.EMAIL,
            subject = null,
            body = "안녕하세요",
        )

        assertEquals("안녕하세요", result.draft.body)
        assertEquals("메시지 드립니다", result.draft.subject)
        assertEquals("user_provided", result.source)
    }

    @Test
    fun `invented phone in generated body is rejected`() {
        val result = SafeMessageDraftGenerator.fromModelOutput(
            request(MessageChannel.SMS, "지난 상담 감사"),
            """{"body":"지난 상담 감사합니다. 010-9999-9999로 연락드리겠습니다."}""",
        )

        assertTrue(result.usedFallback)
        assertEquals("invented_contact", result.validationReason)
        assertFalse(result.draft.body.contains("010-9999-9999"))
    }

    @Test
    fun `unsupported meeting details and delivery claims are rejected`() {
        val details = SafeMessageDraftGenerator.fromModelOutput(
            request(MessageChannel.EMAIL, "지난 미팅 감사"),
            """{"subject":"감사","body":"안녕하세요. 지난 미팅에서 팀원들의 협력이 좋았습니다. 감사합니다."}""",
        )
        val delivery = SafeMessageDraftGenerator.fromModelOutput(
            request(MessageChannel.EMAIL, "지난 미팅 감사"),
            """{"subject":"감사","body":"안녕하세요. 감사 메일을 발송해 드립니다. 감사합니다."}""",
        )

        assertTrue(details.usedFallback)
        assertTrue(details.validationReason.startsWith("unsupported_detail"))
        assertTrue(delivery.usedFallback)
        assertEquals("unsupported_delivery_claim", delivery.validationReason)
    }

    private fun request(channel: MessageChannel, purpose: String) = MessageDraftRequest(
        channel = channel,
        recipientName = "김지원",
        purpose = purpose,
        userContext = purpose,
    )
}
