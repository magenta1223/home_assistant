package com.homeassistant.nlp.backend.utils

import com.homeassistant.core.nlp.LlmResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ToolCallParserTest {
    @Test
    fun `parses prompt-injected tool call json`() {
        val parsed = parseToolCallOrText(
            """{"tool_call":{"name":"memory_candidate_create","arguments":{"domain":"SCHOOL","memory_type":"STATE","content":"A","summary":"B","confidence":0.8}}}""",
        )

        val toolCall = assertIs<LlmResponse.ToolCall>(parsed)
        assertEquals("memory_candidate_create", toolCall.spec.name.value)
        assertEquals("""{"domain":"SCHOOL","memory_type":"STATE","content":"A","summary":"B","confidence":0.8}""", toolCall.spec.arguments.value)
    }

    @Test
    fun `invalid json remains text for ai client error handling`() {
        val parsed = parseToolCallOrText("""{"tool_call":""")

        assertIs<LlmResponse.Text>(parsed)
    }
}
