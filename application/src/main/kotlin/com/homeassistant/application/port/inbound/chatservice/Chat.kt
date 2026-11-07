package com.homeassistant.application.port.inbound.chatservice

/** Answers one deduplicated question using episodes visible to the user. */
interface Chat {
    /**
     * The answer to [request], or [ChatResult.AlreadyHandled] when its key was claimed before and no answer
     * is stored for it, or [ChatResult.Failed] when retrieval or the provider failed.
     */
    fun answer(request: ChatRequest): ChatResult
}
