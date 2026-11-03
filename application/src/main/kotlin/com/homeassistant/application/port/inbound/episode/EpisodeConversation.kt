package com.homeassistant.application.port.inbound.episode

/** Answers one deduplicated question using episodes visible to the participant. */
interface EpisodeConversation {
    /**
     * The answer to [request], or [EpisodeConversationResult.AlreadyHandled] when its key was claimed before and no answer
     * is stored for it, or [EpisodeConversationResult.Failed] when retrieval or the provider failed.
     */
    fun answer(request: EpisodeConversationRequest): EpisodeConversationResult
}
