package com.homeassistant.nlp.backend.anthropic

import com.anthropic.models.messages.Model
import com.homeassistant.core.constants.AppConfig

data class AnthropicConfig(
    val model: Model = Model.CLAUDE_3_5_HAIKU_LATEST,
    val maxTokens: Int = AppConfig.DEFAULT_LLM_MAX_TOKENS,
    val temperature: Double? = null,
)
