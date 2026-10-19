package com.homeassistant.llm.claude.cli

import com.homeassistant.llm.completion.CompletionImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeStreamJsonTest {
    @Test
    fun `user message carries text then base64 images on one line`() {
        val line = ClaudeStreamJson.userMessage("질문", listOf(CompletionImage("page-1.jpg", byteArrayOf(1, 2))))

        assertTrue(line.endsWith("\n") && line.count { it == '\n' } == 1)
        val content = Json.parseToJsonElement(line).jsonObject.getValue("message").jsonObject.getValue("content").jsonArray
        assertEquals("질문", content[0].jsonObject.getValue("text").jsonPrimitive.content)
        val source = content[1].jsonObject.getValue("source").jsonObject
        assertEquals("image/jpeg", source.getValue("media_type").jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2)), source.getValue("data").jsonPrimitive.content)
    }

    @Test
    fun `result reads the last result event and ignores other lines`() {
        val stdout = """
            {"type":"system","subtype":"init"}
            not json
            {"type":"result","is_error":false,"result":"done","structured_output":{"a":1}}
        """.trimIndent()

        val result = ClaudeStreamJson.result(stdout)!!

        assertEquals(false, result.isError)
        assertEquals("done", result.text)
        assertEquals("""{"a":1}""", result.structuredOutput.toString())
    }

    @Test
    fun `missing result and failed result are reported`() {
        assertNull(ClaudeStreamJson.result("""{"type":"assistant"}"""))
        val failed = ClaudeStreamJson.result("""{"type":"result","subtype":"success","is_error":true,"result":"auth"}""")!!
        assertTrue(failed.isError)
        assertNull(failed.structuredOutput)
    }
}
