package com.homeassistant.nlp

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env

enum class LmBackendFactory {
    OLLAMA {
        override fun getBackend(): LlmBackend {
            return OllamaBackend(
                baseUrl = Env[AppConfig.ENV_VAR_OLLAMA_BASE_URL] ?: AppConfig.DEFAULT_OLLAMA_BASE_URL,
                model   = Env[AppConfig.ENV_VAR_OLLAMA_MODEL]    ?: AppConfig.DEFAULT_OLLAMA_MODEL,
            )
        }
    },
    OPENROUTER {
        override fun getBackend(): LlmBackend {
            return OpenRouterBackend(
                apiKey = Env[AppConfig.ENV_VAR_OPENROUTER_API_KEY]
                    ?: error("${AppConfig.ENV_VAR_OPENROUTER_API_KEY} not set"),
                model = Env[AppConfig.ENV_VAR_OPENROUTER_MODEL]
                    ?: AppConfig.DEFAULT_OPENROUTER_MODEL,
            )
        }
    }
    ;

    abstract fun getBackend(): LlmBackend

    companion object {
        fun create(aiProvider: String): LmBackendFactory {
            return entries.find { it.name == aiProvider.uppercase() } ?: OLLAMA
        }
    }
}
