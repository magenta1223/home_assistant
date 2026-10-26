package com.homeassistant.application.port.input.memory.conversation

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

data class MemoryConversationRequestKey(
    val streamId: String,
    val requestId: String,
) {
    init {
        require(streamId.isNotBlank()) { "streamId is required" }
        require(requestId.isNotBlank()) { "requestId is required" }
    }
}

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
    /**
     * The answer to [request], or [MemoryConversationResult.AlreadyHandled] when its key was claimed before and no answer
     * is stored for it, or [MemoryConversationResult.Failed] when retrieval or the provider failed.
     */
    fun answer(request: MemoryConversationRequest): MemoryConversationResult
}
