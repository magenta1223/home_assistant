package com.homeassistant.application.port.inbound.episode

import com.homeassistant.domain.identity.UserId

data class ConversationParticipant(
    val scopeId: String,
    val participantId: String,
    val userId: UserId,
) {
    init {
        require(scopeId.isNotBlank()) { "scopeId is required" }
        require(participantId.isNotBlank()) { "participantId is required" }
    }
}

data class ConversationRequestKey(
    val streamId: String,
    val requestId: String,
) {
    init {
        require(streamId.isNotBlank()) { "streamId is required" }
        require(requestId.isNotBlank()) { "requestId is required" }
    }
}

data class ConversationRequest(
    val participant: ConversationParticipant,
    val key: ConversationRequestKey,
    val question: String,
) {
    init {
        require(question.isNotBlank()) { "question is required" }
    }
}

sealed interface ConversationResult {
    data class AnswerReady(val answer: String) : ConversationResult
    data object AlreadyHandled : ConversationResult
    data object Failed : ConversationResult
}