package com.homeassistant.application.port.inbound.episode.conversation

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

/** Answers one deduplicated question using episodes visible to the participant. */
interface EpisodeConversation {
    /**
     * The answer to [request], or [EpisodeConversationResult.AlreadyHandled] when its key was claimed before and no answer
     * is stored for it, or [EpisodeConversationResult.Failed] when retrieval or the provider failed.
     */
    fun answer(request: EpisodeConversationRequest): EpisodeConversationResult
}
