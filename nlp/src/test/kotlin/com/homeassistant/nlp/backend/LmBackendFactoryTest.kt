package com.homeassistant.nlp.backend

import com.homeassistant.core.constants.AppConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LmBackendFactoryTest {
    @Test
    fun `anthropic requires its own API key`() {
        val error = assertFailsWith<IllegalStateException> {
            LmBackendFactory.create(AiProvider.ANTHROPIC) { key ->
                if (key == AppConfig.ENV_VAR_OPENROUTER_API_KEY) "openrouter-key" else null
            }
        }

        assertTrue(error.message.orEmpty().contains("ANTHROPIC_API_KEY"))
    }
}
