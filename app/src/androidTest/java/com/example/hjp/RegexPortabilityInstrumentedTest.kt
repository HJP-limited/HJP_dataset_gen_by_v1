package com.example.hjp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every pattern the workflow policy compiles, checked against Android's regex engine.
 *
 * The desktop JVM and Android do not accept the same syntax, and the policy compiles its patterns
 * in a companion object — so a pattern Android rejects does not fail a unit test, it throws
 * `ExceptionInInitializerError` on the first real turn and takes the whole agent down. This test
 * exists to make that difference visible on the JVM/Android boundary instead of on a device.
 */
class RegexPortabilityInstrumentedTest {
    @Test
    fun everyWorkflowPatternCompilesOnAndroid() {
        val failures = PATTERNS.mapNotNull { (name, pattern) ->
            try {
                Regex(pattern)
                null
            } catch (error: Throwable) {
                "$name -> ${error::class.java.simpleName}: ${error.message?.take(120)}"
            }
        }
        assertTrue("patterns Android rejects:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private companion object {
        val PATTERNS = listOf(
            "EMAIL" to
                """(?i)\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+(?![A-Z0-9.-])""",
            "PHONE" to """(?<!\d)(?:\+82[- ]?|0)\d{1,2}[- ]?\d{3,4}[- ]?\d{4}(?!\d)""",
            "INVALID_EMAIL_LIKE" to
                """(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+(?:-at-|(?:\s+at\s+))[a-z0-9.-]+(?![a-z0-9.-])""",
            "MALFORMED_SPACED_EMAIL" to
                """(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+\s+[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}(?![a-z0-9.-])""",
            "NAME_RECIPIENT" to """[가-힣]{2,12}(?:에게|한테|와|과)""",
            "NAME_CARD" to """[가-힣]{2,12}\s*명함""",
            "STRICT_LOCAL_DATETIME" to """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""",
            "ABSOLUTE_DATE" to """(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일""",
            "CALENDAR_TIME" to """(오전|오후)\s*(\d{1,2})시(?:\s*(\d{1,2})분)?""",
            "NEXT_WEEKDAY" to """다음\s*주\s*(월|화|수|목|금|토|일)요일""",
            // Taken from the shared lexicon itself rather than copied, so a pattern cannot be
            // changed in production and still be checked here in its old form.
            "UPDATE_VALUE" to com.hjp.agent.core.CardUpdateIntent.UPDATE_VALUE_PATTERN,
            "CLOCK_QUESTION" to com.hjp.agent.core.CurrentDateTimeIntent.CLOCK_QUESTION_PATTERN,
            "COMPETING_ACTION" to com.hjp.agent.core.CurrentDateTimeIntent.COMPETING_ACTION_PATTERN,
            "QUOTED_RECALL" to com.hjp.agent.core.QuotedRecallIntent.PATTERN,
            "ROUTER_REQUESTER_SUBJECT" to "내가|제가|우리가|너한테|너에게",
            "GATEWAY_UPDATE_VERBS" to
                com.hjp.agent.core.CardUpdateIntent.UPDATE_VERBS.joinToString("|"),
            "GATEWAY_CLEAR_VERBS" to
                com.hjp.agent.core.CardUpdateIntent.CLEAR_VERBS.joinToString("|"),
            "GATEWAY_CARD_OBJECTS" to
                com.hjp.agent.core.ContactReadIntent.CARD_OBJECTS.joinToString("|"),
            "ROUTER_REAL_SEND" to
                "(?:(?:실제로|자동(?:으로)?|지금\\s*바로).{0,12}(?:전송|발송|보내)|" +
                    "(?:전송|발송|보내).{0,12}(?:실제로|자동(?:으로)?|지금\\s*바로))",
            "ROUTER_REPLACEMENT" to "([^,.]{2,20}?)\\s*(?:말고|말구|이 아니라|가 아니라|아니라|아니고)\\s+",
            "ROUTER_CONTACT_VALUE" to
                """[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|(?<!\d)0\d{1,2}[- ]?\d{3,4}[- ]?\d{4}(?!\d)""",
            "ROUTER_EXECUTION_VERB" to
                "(작성|발송|전송|생성|등록|추가)\\s*(?:해\\s*줘|해주세요|해라|하자)|" +
                    "(써\\s*줘|보내\\s*줘|만들어\\s*줘|열어\\s*줘|잡아\\s*줘)",
            "ROUTER_QUOTE" to "[‘“\"']([^’”\"']{2,80})[’”\"']",
            "ROUTER_CLAIM_TERM" to "[\\p{IsHangul}A-Za-z0-9]{2,}",
        )
    }
}
