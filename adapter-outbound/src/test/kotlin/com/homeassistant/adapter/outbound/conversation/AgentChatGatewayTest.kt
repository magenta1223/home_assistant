package com.homeassistant.adapter.outbound.conversation

import com.homeassistant.application.port.outbound.episode.conversation.ChatId
import com.homeassistant.application.port.outbound.episode.conversation.ChatResult
import com.homeassistant.llm.conversation.ChatClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentChatGatewayTest {
    @Test
    fun `maps begin continue and end to the agent conversation client`() {
        val client = RecordingClient()
        val gateway = AgentChatGateway(client)

        val conversationId = gateway.begin().getOrThrow()
        val reply = assertIs<ChatResult.Success>(
            gateway.chat(conversationId, "prompt"),
        )
        gateway.end(conversationId)
        gateway.close()

        assertEquals(ChatId("conversation-1"), conversationId)
        assertEquals("answer", reply.answer)
        assertEquals(listOf("conversation-1" to "prompt"), client.turns)
        assertEquals(listOf("conversation-1"), client.ended)
        assertTrue(client.closed)
    }

    @Test
    fun `maps a failed turn without exposing its category`() {
        val gateway = AgentChatGateway(
            RecordingClient(turnResult = Result.failure(IllegalStateException("TIMEOUT"))),
        )

        assertEquals(
            ChatResult.Failure,
            gateway.chat(ChatId("conversation-1"), "prompt"),
        )
    }

    private class RecordingClient(
        private val turnResult: Result<String> = Result.success("answer"),
    ) : ChatClient {
        val turns = mutableListOf<Pair<String, String>>()
        val ended = mutableListOf<String>()
        var closed = false

        override fun begin(): Result<String> = Result.success("conversation-1")

        override fun send(chatId: String, prompt: String): Result<String> {
            turns += chatId to prompt
            return turnResult
        }

        override fun end(chatId: String) {
            ended += chatId
        }

        override fun close() {
            closed = true
        }
    }
}
