package com.homeassistant.application.port.inbound.episode

import com.homeassistant.domain.identity.UserId

data class EpisodeConversationParticipant(
    val scopeId: String,
    val participantId: String,
    val userId: UserId,
) {
    init {
        require(scopeId.isNotBlank()) { "scopeId is required" }
        require(participantId.isNotBlank()) { "participantId is required" }
    }
}

data class EpisodeConversationRequestKey(
    val streamId: String,
    val requestId: String,
) {
    init {
        require(streamId.isNotBlank()) { "streamId is required" }
        require(requestId.isNotBlank()) { "requestId is required" }
    }
}

data class EpisodeConversationRequest(
    val participant: EpisodeConversationParticipant,
    val key: EpisodeConversationRequestKey,
    val question: String,
) {
    init {
        require(question.isNotBlank()) { "question is required" }
    }
}

sealed interface EpisodeConversationResult {
    data class AnswerReady(val answer: String) : EpisodeConversationResult
    data object AlreadyHandled : EpisodeConversationResult
    data object Failed : EpisodeConversationResult
}