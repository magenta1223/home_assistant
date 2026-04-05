package com.homeassistant.nlp.models

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env
import com.homeassistant.core.nlp.PromptConfig
import com.homeassistant.nlp.backend.LmBackendFactory

object AiClientFactory {
    fun create(prompts: PromptConfig): AiClient {
        val aiProvider = LmBackendFactory.Companion.create(Env[AppConfig.ENV_VAR_AI_PROVIDER] ?: "ollama")
        val backend = aiProvider.getBackend()
        return AiClient(backend, prompts)
    }
}
