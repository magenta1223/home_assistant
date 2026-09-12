package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.application.port.output.memory.conversation.ConversationId
import com.homeassistant.application.port.output.memory.conversation.ConversationReply
import com.homeassistant.codex.conversation.CodexAppServer
import com.homeassistant.codex.conversation.CodexThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexConversationGatewayTest {
    @Test
    fun `maps begin continue and end to the owned app server`() {
        val server = RecordingCodexAppServer()
        val gateway = ConversationGatewayFactory.create(server)

        val conversationId = gateway.begin().getOrThrow()
        val reply = assertIs<ConversationReply.Success>(
            gateway.continueConversation(conversationId, "prompt"),
        )
        gateway.end(conversationId)
        gateway.close()

        assertEquals(ConversationId("thread-1"), conversationId)
        assertEquals("answer", reply.answer)
        assertEquals(listOf(CodexThreadId("thread-1") to "prompt"), server.turns)
        assertEquals(listOf(CodexThreadId("thread-1")), server.released)
        assertTrue(server.closed)
    }

    @Test
    fun `maps an app server turn failure without exposing its category`() {
        val gateway = ConversationGatewayFactory.create(
            RecordingCodexAppServer(turnResult = Result.failure(IllegalStateException("TIMEOUT"))),
        )

        assertEquals(
            ConversationReply.Failure,
            gateway.continueConversation(ConversationId("thread-1"), "prompt"),
        )
    }

    private class RecordingCodexAppServer(
        private val turnResult: Result<String> = Result.success("answer"),
    ) : CodexAppServer {
        val turns = mutableListOf<Pair<CodexThreadId, String>>()
        val released = mutableListOf<CodexThreadId>()
        var closed = false

        override fun createThread(): Result<CodexThreadId> = Result.success(CodexThreadId("thread-1"))

        override fun executeTurn(threadId: CodexThreadId, prompt: String): Result<String> {
            turns += threadId to prompt
            return turnResult
        }

        override fun releaseThread(threadId: CodexThreadId) {
            released += threadId
        }

        override fun close() {
            closed = true
        }
    }
}
