package com.homeassistant.application.port.outbound.chatgateway

@JvmInline
value class ChatId(val value: String) {
    init {
        require(value.isNotBlank()) { "Conversation id is required" }
    }
}

sealed interface ChatGatewayResult {
    data class Success(val answer: String) : ChatGatewayResult
    data object Failure : ChatGatewayResult
}

/** Starts, continues, and ends provider-neutral conversations. */
interface ChatGateway {
    fun begin(): Result<ChatId>

    fun chat(
        chatId: ChatId,
        prompt: String,
    ): ChatGatewayResult

    fun end(chatId: ChatId)
}
