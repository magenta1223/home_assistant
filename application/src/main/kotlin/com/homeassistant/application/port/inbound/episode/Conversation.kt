package com.homeassistant.application.port.inbound.episode

/** Answers one deduplicated question using episodes visible to the participant. */
interface Conversation {
    /**
     * The answer to [request], or [ConversationResult.AlreadyHandled] when its key was claimed before and no answer
     * is stored for it, or [ConversationResult.Failed] when retrieval or the provider failed.
     */
    fun answer(request: ConversationRequest): ConversationResult
}
