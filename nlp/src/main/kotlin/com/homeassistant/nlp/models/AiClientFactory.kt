package com.homeassistant.nlp.models

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env
import com.homeassistant.core.nlp.PromptConfig
import com.homeassistant.nlp.backend.LmBackendFactory

object AiClientFactory {
    fun create(prompts: PromptConfig): AiClientImpl {
        val backend = LmBackendFactory.create(Env[AppConfig.ENV_VAR_AI_PROVIDER] ?: "ollama")
        return AiClientImpl(backend, prompts)
    }
}
