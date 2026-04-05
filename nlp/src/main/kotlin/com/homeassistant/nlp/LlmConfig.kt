package com.homeassistant.nlp

interface LlmConfig {
    val maxTokens: Int
    val temperature: Double?
}

data class LlmCallConfig(
    override val maxTokens: Int,
    override val temperature: Double? = null,
) : LlmConfig
