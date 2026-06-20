package com.homeassistant.core.nlp

/**
 * Chat-style message passed to an LLM backend.
 *
 * @property role Role assigned to the message in the model request.
 * @property content Text content sent for that role.
 */
data class Message(
    val role: MessageRole,
    val content: String,
)
