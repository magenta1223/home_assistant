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

/** One user turn sent to a provider conversation, with how the provider gets the context it answers from. */
sealed interface ChatTurn {
    val prompt: String

    /** [prompt] already holds the reference the provider answers from. */
    data class WithReference(override val prompt: String) : ChatTurn

    /** The provider looks context up itself with the context tools, reading as the user [contextToken] stands for. */
    data class WithContextTools(override val prompt: String, val contextToken: String) : ChatTurn
}

/** Starts, continues, and ends provider-neutral conversations. */
interface ChatGateway {
    fun begin(): Result<ChatId>

    /** Sends [turn] to [chatId] and returns the provider's answer. */
    fun chat(
        chatId: ChatId,
        turn: ChatTurn,
    ): ChatGatewayResult

    fun end(chatId: ChatId)
}
