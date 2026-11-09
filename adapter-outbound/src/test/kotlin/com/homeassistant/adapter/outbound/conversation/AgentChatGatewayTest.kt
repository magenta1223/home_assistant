package com.homeassistant.adapter.outbound.conversation

import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.application.port.outbound.chatgateway.ChatGatewayResult
import com.homeassistant.infrastructure.runtime.AbstractRuntime
import com.homeassistant.application.port.outbound.chatgateway.ChatTurn
import com.homeassistant.llm.ChatClient
import com.homeassistant.llm.McpToolServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A turn the client received: chat id, prompt and tool server. */
private typealias Turn = Triple<String, String, McpToolServer?>

class AgentChatGatewayTest {
    @Test
    fun `maps begin continue and end to the agent conversation client`() {
        val client = RecordingClient()
        val gateway = AgentChatGateway(client, TOOL_URL)

        val conversationId = gateway.begin().getOrThrow()
        val reply = assertIs<ChatGatewayResult.Success>(
            gateway.chat(conversationId, ChatTurn.WithReference("prompt")),
        )
        gateway.end(conversationId)

        assertEquals(ChatId("conversation-1"), conversationId)
        assertEquals("answer", reply.answer)
        assertEquals(listOf<Turn>(Triple("conversation-1", "prompt", null)), client.turns)
        assertEquals(listOf("conversation-1"), client.ended)
    }

    @Test
    fun `maps a failed turn without exposing its category`() {
        val gateway = AgentChatGateway(
            RecordingClient(turnResult = Result.failure(IllegalStateException("TIMEOUT"))),
            TOOL_URL,
        )

        assertEquals(
            ChatGatewayResult.Failure,
            gateway.chat(ChatId("conversation-1"), ChatTurn.WithReference("prompt")),
        )
    }

    @Test
    fun `a turn with context tools gives the agent the context tool server with the turn's token`() {
        val client = RecordingClient()
        val turn = ChatTurn.WithContextTools("prompt", "turn-token")

        AgentChatGateway(client, TOOL_URL).chat(ChatId("conversation-1"), turn)

        assertEquals(
            listOf<Turn>(Triple("conversation-1", "prompt", McpToolServer("home", TOOL_URL, "turn-token"))),
            client.turns,
        )
    }

    private class RecordingClient(
        private val turnResult: Result<String> = Result.success("answer"),
    ) : AbstractRuntime(), ChatClient {
        val turns = mutableListOf<Turn>()
        val ended = mutableListOf<String>()
        override fun openChat(): Result<String> = Result.success("conversation-1")

        override fun send(chatId: String, prompt: String, toolServer: McpToolServer?): Result<String> {
            turns += Triple(chatId, prompt, toolServer)
            return turnResult
        }

        override fun closeChat(chatId: String) {
            ended += chatId
        }

        override fun onStart() = Unit

        override fun onStop() = Unit
    }

    private companion object {
        const val TOOL_URL = "http://127.0.0.1:8080/mcp"
    }
}
