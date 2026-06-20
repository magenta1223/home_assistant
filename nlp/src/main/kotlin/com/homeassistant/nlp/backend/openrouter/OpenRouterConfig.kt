package com.homeassistant.nlp.backend.openrouter

import com.homeassistant.core.constants.AppConfig

/**
 * Runtime options used when creating OpenRouter chat requests.
 *
 * @property maxTokens Default maximum completion token budget.
 * @property temperature Optional sampling temperature.
 * @property topP Optional nucleus sampling value.
 */
data class OpenRouterConfig(
    val maxTokens: Int = AppConfig.DEFAULT_LLM_MAX_TOKENS,
    val temperature: Double? = null,
    val topP: Double? = null,
)
