package com.homeassistant.nlp.backend.dto

import com.anthropic.models.messages.Model

/** 생성자에서 주입하는 generation 설정. */
data class AnthropicConfig(
    val model: Model = Model.CLAUDE_3_5_HAIKU_LATEST,
    val temperature: Double? = null,
)
