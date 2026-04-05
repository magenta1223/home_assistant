package com.homeassistant.nlp.backend.ollama

import com.homeassistant.core.nlp.LlmConfig

data class OllamaConfig(
    val think: Boolean = false,
    override val temperature: Double? = null,
    val topK: Int? = null,
    val topP: Double? = null,
    val numPredict: Int? = null,
    val numCtx: Int? = null,
    val seed: Int? = null,
    val repeatPenalty: Double? = null,
    override val maxTokens: Int = 512
): LlmConfig