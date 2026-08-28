package com.homeassistant.application.port.output.memory.conversation

sealed interface ConversationTurnResult {
    data class Success(val answer: String) : ConversationTurnResult
    data class Failure(val category: String) : ConversationTurnResult
}

/** Starts or resumes a short-lived model conversation turn. */
interface ConversationTurnClient {
    fun start(prompt: String, onThreadStarted: (String) -> Unit): ConversationTurnResult
    fun resume(threadId: String, prompt: String): ConversationTurnResult

    /** Ends this application's live use of a conversation thread. */
    fun end(threadId: String)
}
