package com.example.hjp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactSearchPromptParserTest {
    @Test
    fun `extracts name from Korean business card request`() {
        assertEquals("김민수", ContactSearchPromptParser.parse("김민수 명함 찾아줘."))
    }

    @Test
    fun `keeps descriptive contact search terms`() {
        assertEquals(
            "판교에서 만난 AI 개발자",
            ContactSearchPromptParser.parse("판교에서 만난 AI 개발자 찾아줘"),
        )
    }

    @Test
    fun `does not route unrelated conversation`() {
        assertNull(ContactSearchPromptParser.parse("안녕하세요"))
    }
}
