package com.homeassistant.nlp.backend.openrouter

import com.homeassistant.core.nlp.LlmConfig

data class OpenRouterConfig(
    override val temperature: Double? = null,
    override val maxTokens: Int = 512,
    val topP: Double? = null,
): LlmConfig