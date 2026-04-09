package com.homeassistant.nlp.backend.openrouter

import com.homeassistant.core.constants.AppConfig

data class OpenRouterConfig(
    val maxTokens: Int = AppConfig.MAX_TOKENS_CHAT,
    val temperature: Double? = null,
    val topP: Double? = null,
)
