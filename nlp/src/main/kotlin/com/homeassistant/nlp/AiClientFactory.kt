package com.homeassistant.nlp

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env
import com.homeassistant.core.nlcore.PromptConfig

object AiClientFactory {
    fun create(prompts: PromptConfig): AiClient {
        val aiProvider = LmBackendFactory.create(Env[AppConfig.ENV_VAR_AI_PROVIDER] ?: "ollama")
        val backend = aiProvider.getBackend()
        return AiClient(backend, prompts)
    }
}
