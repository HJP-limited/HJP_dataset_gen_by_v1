package com.hjp.agent.routing

import com.hjp.agent.contract.ModelDecision
import com.hjp.agent.contract.ModelToolResponse
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedToolResultFormatterTest {
    @Test
    fun `zero contact results cannot become a fabricated answer`() {
        val decision = GroundedToolResultFormatter.finish(ModelToolResponse(
            "call-1",
            "search_contacts",
            buildJsonObject {
                put("ok", true)
                putJsonObject("data") {
                    putJsonArray("results") { }
                }
            },
        ))

        val text = (decision as ModelDecision.FinalCandidate).draftText
        assertTrue(text.contains("찾지 못했습니다"))
        assertFalse(text.contains("전화"))
        assertFalse(text.contains("@"))
    }

    @Test
    fun `formatter emits only fields present in tool payload`() {
        val decision = GroundedToolResultFormatter.finish(ModelToolResponse(
            "call-2",
            "search_contacts",
            buildJsonObject {
                put("ok", true)
                putJsonObject("data") {
                    put("results", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "테스트김")
                            put("company", "가상회사")
                        })
                    })
                }
            },
        ))

        val text = (decision as ModelDecision.FinalCandidate).draftText
        assertTrue(text.contains("테스트김"))
        assertTrue(text.contains("가상회사"))
        assertFalse(text.contains("대표"))
        assertFalse(text.contains("010-"))
    }
}
