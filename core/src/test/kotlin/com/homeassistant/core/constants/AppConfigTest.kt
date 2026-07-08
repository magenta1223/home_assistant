package com.homeassistant.core.constants

import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {
    @Test
    fun `default openrouter model is gpt 5 5`() {
        assertEquals("openai/gpt-5.5", AppConfig.DEFAULT_OPENROUTER_MODEL)
    }
}
