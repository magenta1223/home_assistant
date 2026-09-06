package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.application.port.output.memory.conversation.ConversationTurnResult
import com.homeassistant.codex.conversation.ConversationClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class IntegrationConversationAdapterTest {
    @Test
    fun `returns the thread created by the integration`() {
        val client = ConversationAdapterFactory.create(FakeConversationClient())

        assertEquals("thread-1", client.create())
    }

    @Test
    fun `propagates integration thread creation failure`() {
        val client = ConversationAdapterFactory.create(
            FakeConversationClient(createResult = Result.failure(IllegalStateException("UNAVAILABLE"))),
        )

        assertEquals("UNAVAILABLE", assertFailsWith<IllegalStateException> { client.create() }.message)
    }

    @Test
    fun `maps integration turn success to application success`() {
        val client = ConversationAdapterFactory.create(FakeConversationClient())

        val result = assertIs<ConversationTurnResult.Success>(client.execute("thread-1", "prompt"))

        assertEquals("answer", result.answer)
    }

    @Test
    fun `maps integration turn failure to application failure without a category`() {
        val client = ConversationAdapterFactory.create(
            FakeConversationClient(turnResult = Result.failure(IllegalStateException("TIMEOUT"))),
        )

        assertEquals(ConversationTurnResult.Failure, client.execute("thread-1", "prompt"))
    }

    private class FakeConversationClient(
        private val createResult: Result<String> = Result.success("thread-1"),
        private val turnResult: Result<String> = Result.success("answer"),
    ) : ConversationClient {
        override fun isAvailable(): Boolean = true
        override fun startServer(): Boolean = true
        override fun create(): Result<String> = createResult
        override fun execute(threadId: String, prompt: String): Result<String> = turnResult
        override fun end(threadId: String) = Unit
        override fun close() = Unit
    }
}
