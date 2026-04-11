package com.homeassistant.nlp.backend

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env
import com.homeassistant.nlp.backend.anthropic.AnthropicBackend
import com.homeassistant.nlp.backend.ollama.OllamaBackend
import com.homeassistant.nlp.backend.ollama.OllamaConfig
import com.homeassistant.nlp.backend.openrouter.OpenRouterBackend
import com.homeassistant.nlp.backend.openrouter.OpenRouterConfig
import com.homeassistant.core.nlp.LlmBackend

object LmBackendFactory {
    fun create(aiProvider: AiProvider): LlmBackend = when (aiProvider) {
        AiProvider.OLLAMA -> OllamaBackend(
            baseUrl = Env[AppConfig.ENV_VAR_OLLAMA_BASE_URL] ?: AppConfig.DEFAULT_OLLAMA_BASE_URL,
            model = Env[AppConfig.ENV_VAR_OLLAMA_MODEL] ?: AppConfig.DEFAULT_OLLAMA_MODEL,
            config = OllamaConfig(),
        )
        AiProvider.OPENROUTER -> OpenRouterBackend(
            apiKey = Env[AppConfig.ENV_VAR_OPENROUTER_API_KEY]
                ?: error("${AppConfig.ENV_VAR_OPENROUTER_API_KEY} not set"),
            model = Env[AppConfig.ENV_VAR_OPENROUTER_MODEL]
                ?: AppConfig.DEFAULT_OPENROUTER_MODEL,
            config = OpenRouterConfig(),
        )
        AiProvider.ANTHROPIC -> AnthropicBackend(
            apiKey = Env[AppConfig.ENV_VAR_API_KEY]
                ?: error("${AppConfig.ENV_VAR_API_KEY} not set"),
        )
    }
}