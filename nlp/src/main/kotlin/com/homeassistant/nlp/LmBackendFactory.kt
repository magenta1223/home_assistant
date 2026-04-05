package com.homeassistant.nlp

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env

enum class LmBackendFactory {
    OPENROUTER {
        override fun getBackend(): LlmBackend {
            return OpenRouterBackend(
                apiKey = Env[AppConfig.ENV_VAR_OPENROUTER_API_KEY]
                    ?: error("${AppConfig.ENV_VAR_OPENROUTER_API_KEY} not set"),
                model = Env[AppConfig.ENV_VAR_OPENROUTER_MODEL]
                    ?: AppConfig.DEFAULT_OPENROUTER_MODEL,
            )
        }
    },
//    ANTHROPIC {
//        override fun getBackend(): LlmBackend {
//            return AnthropicBackend(
//                apiKey = Env[AppConfig.ENV_VAR_API_KEY]
//                    ?: error("${AppConfig.ENV_VAR_API_KEY} not set"),
//            )
//        }
//    },
    CLAUDE_CLI {
        override fun getBackend(): LlmBackend = ClaudeCliBackend()
    },
//    NONE {
//        override fun getBackend(): LlmBackend {
//            return OpenRouterBackend(
//                apiKey = Env[AppConfig.ENV_VAR_OPENROUTER_API_KEY]
//                    ?: error("${AppConfig.ENV_VAR_OPENROUTER_API_KEY} not set"),
//                model = Env[AppConfig.ENV_VAR_OPENROUTER_MODEL]
//                    ?: AppConfig.DEFAULT_OPENROUTER_MODEL,
//            )
//        }
//    }
    ;

    abstract fun getBackend(): LlmBackend

    companion object {
        fun create(aiProvider: String): LmBackendFactory {
            return entries.find { it.name == aiProvider.uppercase() } ?: CLAUDE_CLI
        }
    }
}
