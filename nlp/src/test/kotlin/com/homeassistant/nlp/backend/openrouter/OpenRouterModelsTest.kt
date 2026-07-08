package com.homeassistant.nlp.backend.openrouter

import com.homeassistant.core.utils.JsonSerializer.encodeToString
import com.homeassistant.core.utils.JsonSerializer.parseToJsonElement
import com.homeassistant.nlp.backend.ollama.OllamaOptions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class OpenRouterModelsTest {

    @Test
    fun `default max tokens is large enough for topic analysis json`() {
        assertEquals(8192, OpenRouterConfig().maxTokens)
    }

    @Test
    fun `request serializes response format schema`() {
        val request = OpenRouterRequest(
            model = "google/gemini-2.5-flash-lite",
            messages = listOf(OpenRouterMessage("user", "analyze")),
            response_format = OpenRouterResponseFormat(
                json_schema = OpenRouterJsonSchemaResponseFormat(
                    name = "topic_analysis_output",
                    schema = """{"type":"object"}""".parseToJsonElement(),
                ),
            ),
        )

        val encoded = request.encodeToString()

        assertContains(encoded, "response_format")
        assertContains(encoded, "json_schema")
        assertContains(encoded, "topic_analysis_output")
        assertContains(encoded.compactJson(), """"schema":{"type":"object"}""")
    }

    @Test
    fun `response parser preserves openrouter error body`() {
        val error = assertFailsWith<OpenRouterApiException> {
            OpenRouterResponseParser.parse(
                statusCode = 400,
                body = """{"error":{"message":"response_format is not supported by this model"}}""",
            )
        }

        assertContains(error.message ?: "", "OpenRouter API error 400")
        assertContains(error.message ?: "", "response_format is not supported")
        assertContains(error.responseBody, "response_format is not supported")
    }

    @Test
    fun `response parser reads token usage`() {
        val response = OpenRouterResponseParser.parse(
            statusCode = 200,
            body = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "{\"topics\":[]}"
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 120,
                    "completion_tokens": 34,
                    "total_tokens": 154
                  }
                }
            """.trimIndent(),
        )

        assertEquals(120, response.usage?.prompt_tokens)
        assertEquals(34, response.usage?.completion_tokens)
        assertEquals(154, response.usage?.total_tokens)
    }

    @Test
    fun `ollama options serialize with kotlin property names`() {
        val encoded = OllamaOptions(
            topK = 40,
            topP = 0.9,
            numPredict = 256,
            numCtx = 4096,
            repeatPenalty = 1.1
        ).encodeToString()

        val compact = encoded.compactJson()
        assertContains(compact, """"topK":40""")
        assertContains(compact, """"topP":0.9""")
        assertContains(compact, """"numPredict":256""")
        assertContains(compact, """"numCtx":4096""")
        assertContains(compact, """"repeatPenalty":1.1""")
        assertFalse(encoded.contains("top_k"))
        assertFalse(encoded.contains("num_predict"))
        assertFalse(encoded.contains("repeat_penalty"))
    }

    private fun String.compactJson(): String = filterNot { it.isWhitespace() }
}
