package com.homeassistant.nlp

import com.homeassistant.constants.AppConfig
import com.homeassistant.constants.Env

object AiClientFactory {
    fun create(): AiClient {
        val provider = Env[AppConfig.ENV_VAR_AI_PROVIDER] ?: "anthropic"
        val backend = when (provider.lowercase()) {
            "openrouter" -> OpenRouterBackend(
                apiKey = Env[AppConfig.ENV_VAR_OPENROUTER_API_KEY]
                    ?: error("${AppConfig.ENV_VAR_OPENROUTER_API_KEY} not set"),
                model = Env[AppConfig.ENV_VAR_OPENROUTER_MODEL]
                    ?: AppConfig.DEFAULT_OPENROUTER_MODEL,
            )
            else -> AnthropicBackend(
                apiKey = Env[AppConfig.ENV_VAR_API_KEY]
                    ?: error("${AppConfig.ENV_VAR_API_KEY} not set"),
            )
        }
        return AiClient(backend)
    }
}
