package com.homeassistant.adapter.outbound.conversation

import com.homeassistant.application.port.outbound.chatgateway.ChatGateway
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.application.port.outbound.chatgateway.ChatGatewayResult
import com.homeassistant.application.port.outbound.chatgateway.ChatTurn
import com.homeassistant.llm.ChatClient
import com.homeassistant.llm.McpToolServer
import org.slf4j.LoggerFactory

/**
 * Adapts an agent backend [ChatClient] to the application conversation port. A turn with context tools lets the agent
 * call the context tool server at [contextToolUrl] as the user of the turn's context token.
 */
class AgentChatGateway(
    private val client: ChatClient,
    private val contextToolUrl: String,
) : ChatGateway {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun begin(): Result<ChatId> = client.openChat().map(::ChatId)

    override fun chat(chatId: ChatId, turn: ChatTurn): ChatGatewayResult =
        client.send(chatId.value, turn.prompt, toolServerFor(turn)).fold(
            onSuccess = ChatGatewayResult::Success,
            onFailure = { error ->
                log.warn("Conversation turn failed category={}", error.message ?: error.javaClass.simpleName)
                ChatGatewayResult.Failure
            },
        )

    override fun end(chatId: ChatId) {
        client.closeChat(chatId.value)
    }

    private fun toolServerFor(turn: ChatTurn): McpToolServer? = when (turn) {
        is ChatTurn.WithReference -> null
        is ChatTurn.WithContextTools -> McpToolServer(CONTEXT_TOOL_SERVER_NAME, contextToolUrl, turn.contextToken)
    }

    private companion object {
        /** The agent sees the context tools as `mcp__home__<tool>`. */
        const val CONTEXT_TOOL_SERVER_NAME = "home"
    }
}
