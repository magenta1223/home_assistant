package com.homeassistant.nlp.backend.openrouter

import com.homeassistant.core.constants.AppConfig

data class OpenRouterConfig(
    val maxTokens: Int = AppConfig.DEFAULT_LLM_MAX_TOKENS,
    val temperature: Double? = null,
    val topP: Double? = null,
)
