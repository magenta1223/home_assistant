package com.homeassistant.nlp.backend.openrouter

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
}
