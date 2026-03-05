package com.homeassistant.nlp

import com.homeassistant.constants.AppConfig

object AiClientFactory {
    fun create(): AiClient {
        val provider = System.getenv(AppConfig.ENV_VAR_AI_PROVIDER) ?: "anthropic"
        val backend = when (provider.lowercase()) {
            "openrouter" -> OpenRouterBackend(
                apiKey = System.getenv(AppConfig.ENV_VAR_OPENROUTER_API_KEY)
                    ?: error("${AppConfig.ENV_VAR_OPENROUTER_API_KEY} not set"),
                model = System.getenv(AppConfig.ENV_VAR_OPENROUTER_MODEL)
                    ?: AppConfig.DEFAULT_OPENROUTER_MODEL,
            )
            else -> AnthropicBackend(
                apiKey = System.getenv(AppConfig.ENV_VAR_API_KEY)
                    ?: error("${AppConfig.ENV_VAR_API_KEY} not set"),
            )
        }
        return AiClient(backend)
    }
}
