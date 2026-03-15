package com.homeassistant.constants

import com.homeassistant.nlp.AnthropicBackend
import com.homeassistant.nlp.LlmBackend
import com.homeassistant.nlp.OpenRouterBackend

enum class AiProvider {
    ANTHROPIC {
        override fun getBackend(): LlmBackend {
            return OpenRouterBackend(
                apiKey = Env[AppConfig.ENV_VAR_OPENROUTER_API_KEY]
                    ?: error("${AppConfig.ENV_VAR_OPENROUTER_API_KEY} not set"),
                model = Env[AppConfig.ENV_VAR_OPENROUTER_MODEL]
                    ?: AppConfig.DEFAULT_OPENROUTER_MODEL,
            )
        }
    },
    OPENROUTER {
        override fun getBackend(): LlmBackend {
            return AnthropicBackend(
            apiKey = Env[AppConfig.ENV_VAR_API_KEY]
                ?: error("${AppConfig.ENV_VAR_API_KEY} not set"),
            )
        }
    },
    NONE {
        override fun getBackend(): LlmBackend {
            return OpenRouterBackend(
                apiKey = Env[AppConfig.ENV_VAR_OPENROUTER_API_KEY]
                    ?: error("${AppConfig.ENV_VAR_OPENROUTER_API_KEY} not set"),
                model = Env[AppConfig.ENV_VAR_OPENROUTER_MODEL]
                    ?: AppConfig.DEFAULT_OPENROUTER_MODEL,
            )
        }
    };

    abstract fun getBackend(): LlmBackend

    companion object {
        fun create(aiProvider: String): AiProvider {
            return  entries.find { it.name == aiProvider } ?: NONE
        }
    }
}