package com.homeassistant.application.port.inbound.chatservice

/** Answers one question using episodes visible to the user. */
interface Chat {
    /** The answer to [request], or [ChatResult.Failed] when retrieval or the provider failed. */
    fun answer(request: ChatRequest): ChatResult
}
