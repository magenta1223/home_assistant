package com.homeassistant.core.nlp

data class Message(
    val role: MessageRole,
    val content: String,
)
