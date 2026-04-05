package com.homeassistant.nlp.backend.anthropic

import com.anthropic.models.messages.Model
import com.homeassistant.core.nlp.LlmConfig

/** 생성자에서 주입하는 generation 설정. */
data class AnthropicConfig(
    val model: Model = Model.CLAUDE_3_5_HAIKU_LATEST,
    override val temperature: Double? = null,
    override val maxTokens: Int = 512
): LlmConfig
