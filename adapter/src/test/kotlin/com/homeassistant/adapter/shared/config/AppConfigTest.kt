package com.homeassistant.adapter.shared.config

import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {
    @Test
    fun `default embedding configuration uses ollama multilingual e5 base`() {
        assertEquals("qllama/multilingual-e5-base", AppConfig.DEFAULT_EMBEDDING_MODEL_NAME)
        assertEquals(768, AppConfig.DEFAULT_EMBEDDING_VECTOR_SIZE)
    }
}
