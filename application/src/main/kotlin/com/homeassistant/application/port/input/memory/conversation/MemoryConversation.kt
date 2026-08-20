package com.homeassistant.application.port.input.memory.conversation

import com.homeassistant.application.port.input.memory.answer.ConversationRequestKey
import com.homeassistant.domain.identity.UserId

data class MemoryConversationParticipant(
    val scopeId: String,
    val participantId: String,
    val userId: UserId,
) {
    init {
        require(scopeId.isNotBlank()) { "scopeId is required" }
        require(participantId.isNotBlank()) { "participantId is required" }
    }
}

typealias MemoryConversationRequestKey = ConversationRequestKey

data class MemoryConversationRequest(
    val participant: MemoryConversationParticipant,
    val key: MemoryConversationRequestKey,
    val question: String,
) {
    init {
        require(question.isNotBlank()) { "question is required" }
    }
}

sealed interface MemoryConversationResult {
    data class AnswerReady(val answer: String) : MemoryConversationResult
    data object AlreadyHandled : MemoryConversationResult
    data object Failed : MemoryConversationResult
}

/** Answers one deduplicated question using memories visible to the participant. */
interface MemoryConversation {
    fun answer(request: MemoryConversationRequest): MemoryConversationResult

    /** Records successful delivery by an inbound adapter. */
    fun markDelivered(key: MemoryConversationRequestKey, deliveryId: String)
}
