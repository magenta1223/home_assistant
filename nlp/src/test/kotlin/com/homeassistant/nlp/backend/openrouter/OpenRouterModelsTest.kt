package com.homeassistant.nlp.backend.openrouter

import com.homeassistant.core.utils.JsonSerializer.encodeToString
import com.homeassistant.core.utils.JsonSerializer.parseToJsonElement
import com.homeassistant.nlp.backend.ollama.OllamaOptions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
