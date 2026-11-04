package com.homeassistant.application.port.outbound.episode.conversation

@JvmInline
value class ChatId(val value: String) {
    init {
        require(value.isNotBlank()) { "Conversation id is required" }
    }
}

sealed interface ChatResult {
    data class Success(val answer: String) : ChatResult
    data object Failure : ChatResult
}

/** Starts, continues, and ends provider-neutral conversations. */
interface ChatGateway : AutoCloseable {
    fun begin(): Result<ChatId>

    fun chat(
        chatId: ChatId,
        prompt: String,
    ): ChatResult

    fun end(chatId: ChatId)
}
