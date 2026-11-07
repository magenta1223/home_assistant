package com.homeassistant.application.port.inbound.chatservice

import com.homeassistant.domain.identity.UserId

data class ChatRequest(
    val userId: UserId,
    val question: String,
) {
    init {
        require(question.isNotBlank()) { "question is required" }
    }
}

sealed interface ChatResult {
    data class AnswerReady(val answer: String) : ChatResult
    data object Failed : ChatResult
}
