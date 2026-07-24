package com.hjp.agent.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactGroundingGuardTest {
    @Test
    fun `contact-like prompts require evidence but small talk does not`() {
        assertTrue(ContactGroundingGuard.requiresToolEvidence("김지원 명함을 찾아줘."))
        assertTrue(ContactGroundingGuard.requiresToolEvidence("김지원에게 연락하려고 해. 연락처를 보여줘."))
        assertTrue(ContactGroundingGuard.requiresToolEvidence("비전글로벌 대표의 명함을 확인해 줘."))
        assertFalse(ContactGroundingGuard.requiresToolEvidence("오늘 기분이 어때?"))
        assertFalse(ContactGroundingGuard.requiresToolEvidence("내일 회의를 일정에 추가해 줘."))
        assertTrue(ContactGroundingGuard.requiresAnyToolEvidence("내일 회의를 일정에 추가해 줘."))
        assertTrue(ContactGroundingGuard.requiresAnyToolEvidence("지금 몇 시야?"))
        assertFalse(ContactGroundingGuard.requiresAnyToolEvidence("오늘 기분이 어때?"))
    }
}
