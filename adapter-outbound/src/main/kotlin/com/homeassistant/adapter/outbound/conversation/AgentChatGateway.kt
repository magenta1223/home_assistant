package com.homeassistant.adapter.outbound.conversation

import com.homeassistant.application.port.outbound.episode.conversation.ChatGateway
import com.homeassistant.application.port.outbound.episode.conversation.ChatId
import com.homeassistant.application.port.outbound.episode.conversation.ChatResult
import com.homeassistant.llm.conversation.ChatClient
import org.slf4j.LoggerFactory

/** Adapts an agent backend [ChatClient] to the application conversation port. */
class AgentChatGateway(
    private val client: ChatClient,
) : ChatGateway {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun begin(): Result<ChatId> = client.begin().map(::ChatId)

    override fun chat(chatId: ChatId, prompt: String): ChatResult =
        client.send(chatId.value, prompt).fold(
            onSuccess = ChatResult::Success,
            onFailure = { error ->
                log.warn("Conversation turn failed category={}", error.message ?: error.javaClass.simpleName)
                ChatResult.Failure
            },
        )

    override fun end(chatId: ChatId) {
        client.end(chatId.value)
    }

    override fun close() {
        client.close()
    }
}
