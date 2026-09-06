package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.application.port.output.memory.conversation.ConversationTurnResult
import com.homeassistant.codex.conversation.ConversationClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IntegrationConversationTurnClientTest {
    @Test
    fun `maps integration success to application success`() {
        val client = ConversationTurnClientFactory.create(FakeConversationClient(Result.success("answer")))

        val result = assertIs<ConversationTurnResult.Success>(client.start("prompt") {})

        assertEquals("answer", result.answer)
    }

    @Test
    fun `maps integration failure category to application failure`() {
        val client = ConversationTurnClientFactory.create(
            FakeConversationClient(Result.failure(IllegalStateException("TIMEOUT"))),
        )

        val result = assertIs<ConversationTurnResult.Failure>(client.start("prompt") {})

        assertEquals("TIMEOUT", result.category)
    }

    private class FakeConversationClient(
        private val result: Result<String>,
    ) : ConversationClient {
        override fun isAvailable(): Boolean = true
        override fun startServer(): Boolean = true
        override fun start(prompt: String, onThreadStarted: (String) -> Unit): Result<String> = result
        override fun resume(threadId: String, prompt: String): Result<String> = result
        override fun end(threadId: String) = Unit
        override fun close() = Unit
    }
}
