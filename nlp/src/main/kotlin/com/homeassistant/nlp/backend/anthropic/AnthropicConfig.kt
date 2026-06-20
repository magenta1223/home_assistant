package com.homeassistant.nlp.backend.anthropic

import com.anthropic.models.messages.Model
import com.homeassistant.core.constants.AppConfig

/**
 * Runtime options used when creating Anthropic message requests.
 *
 * @property model Anthropic model identifier to call.
 * @property maxTokens Default maximum completion token budget.
 * @property temperature Optional sampling temperature.
 */
data class AnthropicConfig(
    val model: Model = Model.CLAUDE_3_5_HAIKU_LATEST,
    val maxTokens: Int = AppConfig.DEFAULT_LLM_MAX_TOKENS,
    val temperature: Double? = null,
)
