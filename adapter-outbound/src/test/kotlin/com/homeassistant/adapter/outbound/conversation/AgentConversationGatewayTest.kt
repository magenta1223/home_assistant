package com.homeassistant.adapter.outbound.conversation

import com.homeassistant.application.port.output.memory.conversation.ConversationId
import com.homeassistant.application.port.output.memory.conversation.ConversationReply
import com.homeassistant.llm.conversation.ConversationClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentConversationGatewayTest {
    @Test
    fun `maps begin continue and end to the agent conversation client`() {
        val client = RecordingClient()
        val gateway = AgentConversationGateway(client)

        val conversationId = gateway.begin().getOrThrow()
        val reply = assertIs<ConversationReply.Success>(
            gateway.continueConversation(conversationId, "prompt"),
        )
        gateway.end(conversationId)
        gateway.close()

        assertEquals(ConversationId("conversation-1"), conversationId)
        assertEquals("answer", reply.answer)
        assertEquals(listOf("conversation-1" to "prompt"), client.turns)
        assertEquals(listOf("conversation-1"), client.ended)
        assertTrue(client.closed)
    }

    @Test
    fun `maps a failed turn without exposing its category`() {
        val gateway = AgentConversationGateway(
            RecordingClient(turnResult = Result.failure(IllegalStateException("TIMEOUT"))),
        )

        assertEquals(
            ConversationReply.Failure,
            gateway.continueConversation(ConversationId("conversation-1"), "prompt"),
        )
    }

    private class RecordingClient(
        private val turnResult: Result<String> = Result.success("answer"),
    ) : ConversationClient {
        val turns = mutableListOf<Pair<String, String>>()
        val ended = mutableListOf<String>()
        var closed = false

        override fun begin(): Result<String> = Result.success("conversation-1")

        override fun send(conversationId: String, prompt: String): Result<String> {
            turns += conversationId to prompt
            return turnResult
        }

        override fun end(conversationId: String) {
            ended += conversationId
        }

        override fun close() {
            closed = true
        }
    }
}
