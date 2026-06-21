package com.homeassistant.nlp.backend.openrouter

import com.homeassistant.nlp.backend.ollama.OllamaOptions
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpenRouterModelsTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `default max tokens is large enough for topic analysis json`() {
        assertEquals(2048, OpenRouterConfig().maxTokens)
    }

    @Test
    fun `request serializes response format schema`() {
        val request = OpenRouterRequest(
            model = "google/gemini-2.5-flash-lite",
            messages = listOf(OpenRouterMessage("user", "analyze")),
            response_format = OpenRouterResponseFormat(
                json_schema = OpenRouterJsonSchemaResponseFormat(
                    name = "topic_analysis_output",
                    schema = Json.parseToJsonElement("""{"type":"object"}"""),
                ),
            ),
        )

        val encoded = json.encodeToString(request)

        assertContains(encoded, "response_format")
        assertContains(encoded, "json_schema")
        assertContains(encoded, "topic_analysis_output")
        assertContains(encoded, """"schema":{"type":"object"}""")
    }

    @Test
    fun `ollama options serialize with kotlin property names`() {
        val encoded = json.encodeToString(
            OllamaOptions(topK = 40, topP = 0.9, numPredict = 256, numCtx = 4096, repeatPenalty = 1.1),
        )

        assertContains(encoded, """"topK":40""")
        assertContains(encoded, """"topP":0.9""")
        assertContains(encoded, """"numPredict":256""")
        assertContains(encoded, """"numCtx":4096""")
        assertContains(encoded, """"repeatPenalty":1.1""")
        assertFalse(encoded.contains("top_k"))
        assertFalse(encoded.contains("num_predict"))
        assertFalse(encoded.contains("repeat_penalty"))
    }
}
