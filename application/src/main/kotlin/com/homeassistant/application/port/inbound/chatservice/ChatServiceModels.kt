package com.homeassistant.application.port.inbound.chatservice

import com.homeassistant.domain.identity.UserId

data class ChatRequestKey(
    val userId: UserId,
    val requestId: String,
) {
    init {
        require(requestId.isNotBlank()) { "requestId is required" }
    }
}

data class ChatRequest(
    val userId: UserId,
    val requestId: String,
    val question: String,
) {
    init {
        require(question.isNotBlank()) { "question is required" }
    }

    val key: ChatRequestKey get() = ChatRequestKey(userId, requestId)
}

sealed interface ChatResult {
    data class AnswerReady(val answer: String) : ChatResult
    data object AlreadyHandled : ChatResult
    data object Failed : ChatResult
}
