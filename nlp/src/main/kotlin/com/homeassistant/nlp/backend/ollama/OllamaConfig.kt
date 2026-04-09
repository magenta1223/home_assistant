package com.homeassistant.nlp.backend.ollama

import com.homeassistant.core.constants.AppConfig

data class OllamaConfig(
    val maxTokens: Int = AppConfig.MAX_TOKENS_CHAT,
    val temperature: Double? = null,
    val think: Boolean = false,
    val topK: Int? = null,
    val topP: Double? = null,
    val numPredict: Int? = null,
    val numCtx: Int? = null,
    val seed: Int? = null,
    val repeatPenalty: Double? = null,
)
