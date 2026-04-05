package com.homeassistant.nlp.backend

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env
import com.homeassistant.nlp.backend.impl.OllamaBackend
import com.homeassistant.nlp.backend.dto.OllamaConfig
import com.homeassistant.nlp.backend.impl.OpenRouterBackend
import com.homeassistant.nlp.backend.dto.OpenRouterConfig
import com.homeassistant.nlp.backend.interfaces.LlmBackend

object LmBackendFactory {
    fun create(aiProvider: String): LlmBackend {
        return when (aiProvider) {
            "ollama" -> OllamaBackend(
                baseUrl = Env[AppConfig.ENV_VAR_OLLAMA_BASE_URL] ?: AppConfig.DEFAULT_OLLAMA_BASE_URL,
                model = Env[AppConfig.ENV_VAR_OLLAMA_MODEL] ?: AppConfig.DEFAULT_OLLAMA_MODEL,
                config = OllamaConfig(),
            )

            "openrouter" -> OpenRouterBackend(
                apiKey = Env[AppConfig.ENV_VAR_OPENROUTER_API_KEY]
                    ?: error("${AppConfig.ENV_VAR_OPENROUTER_API_KEY} not set"),
                model = Env[AppConfig.ENV_VAR_OPENROUTER_MODEL]
                    ?: AppConfig.DEFAULT_OPENROUTER_MODEL,
                config = OpenRouterConfig(),
            )
            else -> throw IllegalArgumentException("Unknown LlmBackend provider: $aiProvider")
        }

    }

}