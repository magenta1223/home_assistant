package com.homeassistant.adapter.outbound.conversation

import com.homeassistant.application.port.outbound.chatgateway.ChatGateway
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.application.port.outbound.chatgateway.ChatGatewayResult
import com.homeassistant.llm.ChatClient
import org.slf4j.LoggerFactory

/** Adapts an agent backend [ChatClient] to the application conversation port. */
class AgentChatGateway(
    private val client: ChatClient,
) : ChatGateway {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun begin(): Result<ChatId> = client.begin().map(::ChatId)

    override fun chat(chatId: ChatId, prompt: String): ChatGatewayResult =
        client.send(chatId.value, prompt).fold(
            onSuccess = ChatGatewayResult::Success,
            onFailure = { error ->
                log.warn("Conversation turn failed category={}", error.message ?: error.javaClass.simpleName)
                ChatGatewayResult.Failure
            },
        )

    override fun end(chatId: ChatId) {
        client.end(chatId.value)
    }

    override fun close() {
        client.close()
    }
}
