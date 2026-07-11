package com.homeassistant.core.constants

import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {
    @Test
    fun `default openrouter model is gpt 5 5`() {
        assertEquals("openai/gpt-5.5", AppConfig.DEFAULT_OPENROUTER_MODEL)
    }

    @Test
    fun `default AI provider is ollama`() {
        assertEquals("ollama", AppConfig.DEFAULT_AI_PROVIDER)
    }

    @Test
    fun `default embedding configuration uses ollama multilingual e5 base`() {
        assertEquals("qllama/multilingual-e5-base", AppConfig.DEFAULT_EMBEDDING_MODEL_NAME)
        assertEquals(768, AppConfig.DEFAULT_EMBEDDING_VECTOR_SIZE)
    }
}
