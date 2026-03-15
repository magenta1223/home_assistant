package com.homeassistant.nlp

import com.homeassistant.constants.AiProvider
import com.homeassistant.constants.AppConfig
import com.homeassistant.constants.Env

object AiClientFactory {
    fun create(): AiClient {
        val aiProvider = AiProvider.create(Env[AppConfig.ENV_VAR_AI_PROVIDER] ?: "anthropic")
        val backend = aiProvider.getBackend()
        return AiClient(backend)
    }
}
