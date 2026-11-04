package com.homeassistant.application.port.inbound.chatservice

import com.homeassistant.domain.identity.UserId

data class ChatParticipant(
    val scopeId: String,
    val participantId: String,
    val userId: UserId,
) {
    init {
        require(scopeId.isNotBlank()) { "scopeId is required" }
        require(participantId.isNotBlank()) { "participantId is required" }
    }
}

data class ChatRequestKey(
    val streamId: String,
    val requestId: String,
) {
    init {
        require(streamId.isNotBlank()) { "streamId is required" }
        require(requestId.isNotBlank()) { "requestId is required" }
    }
}

data class ChatRequest(
    val participant: ChatParticipant,
    val key: ChatRequestKey,
    val question: String,
) {
    init {
        require(question.isNotBlank()) { "question is required" }
    }
}

sealed interface ChatResult {
    data class AnswerReady(val answer: String) : ChatResult
    data object AlreadyHandled : ChatResult
    data object Failed : ChatResult
}