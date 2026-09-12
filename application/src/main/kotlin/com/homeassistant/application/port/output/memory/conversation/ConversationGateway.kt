package com.homeassistant.application.port.output.memory.conversation

@JvmInline
value class ConversationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Conversation id is required" }
    }
}

sealed interface ConversationReply {
    data class Success(val answer: String) : ConversationReply
    data object Failure : ConversationReply
}

/** Starts, continues, and ends provider-neutral conversations. */
interface ConversationGateway : AutoCloseable {
    fun begin(): Result<ConversationId>

    fun continueConversation(
        conversationId: ConversationId,
        prompt: String,
    ): ConversationReply

    fun end(conversationId: ConversationId)
}
