package com.homeassistant.core.nlp

interface LlmConfig {
    val maxTokens: Int
    val temperature: Double?
}