package com.homeassistant.application.port.output.memory.conversation

sealed interface ConversationTurnResult {
    data class Success(val answer: String) : ConversationTurnResult
    data object Failure : ConversationTurnResult
}

/** Executes one turn in a conversation thread. */
interface ConversationTurnExecutor {
    fun execute(threadId: String, prompt: String): ConversationTurnResult
}
