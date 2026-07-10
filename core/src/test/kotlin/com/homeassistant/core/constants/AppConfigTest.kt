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
    fun `default embedding configuration uses local multilingual e5 small`() {
        assertEquals("EMBEDDING_MODEL_PATH", AppConfig.ENV_VAR_EMBEDDING_MODEL_PATH)
        assertEquals("intfloat/multilingual-e5-small", AppConfig.DEFAULT_EMBEDDING_MODEL_NAME)
        assertEquals(384, AppConfig.DEFAULT_EMBEDDING_VECTOR_SIZE)
    }
}
