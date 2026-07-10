package com.homeassistant.nlp.backend.ollama

import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaConfigTest {
    @Test
    fun `explicit numPredict takes precedence over maxTokens`() {
        assertEquals(256, OllamaConfig(maxTokens = 8192, numPredict = 256).effectiveNumPredict())
    }

    @Test
    fun `maxTokens is used when numPredict is absent`() {
        assertEquals(8192, OllamaConfig(maxTokens = 8192).effectiveNumPredict())
    }
}
