package com.homeassistant.nlp.backend.interfaces

data class LlmConfig(
    val maxTokens: Int,
    val temperature: Double?
)