package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.output.memory.conversation.ConversationThreadLifecycle
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationReceipt
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSession
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionLease
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionStore
import com.homeassistant.domain.identity.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpireIdleMemoryConversationsTest {
    @Test
    fun `releases every session idle for ten minutes`() {
        val store = ExpiringStore(
            listOf(
                session(1, "thread-a", NOW - TIMEOUT),
                session(2, "thread-b", NOW - TIMEOUT - 1),
                session(3, "thread-c", NOW - TIMEOUT + 1),
            ),
        )
        val client = EndingClient()
        val useCase = ExpireIdleMemoryConversations(
            sessions = store,
            threadLifecycle = client,
            clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
        )

        assertEquals(2, useCase.execute())
        assertEquals(listOf("thread-a", "thread-b"), client.ended)
        assertEquals(listOf("thread-c"), store.remaining.map { it.conversationThreadId })
    }

    private class EndingClient : ConversationThreadLifecycle {
        val ended = mutableListOf<String>()
        override fun create(): String = error("unused")
        override fun end(threadId: String) {
            ended += threadId
        }
    }

    private class ExpiringStore(sessions: List<MemoryConversationSession>) : MemoryConversationSessionStore {
        val remaining = sessions.toMutableList()

        override fun expireIdle(beforeInclusive: Long): List<MemoryConversationSession> {
            val expired = remaining.filter { it.lastActiveAt <= beforeInclusive }
            remaining.removeAll(expired)
            return expired
        }

        override fun claimRequest(key: MemoryConversationRequestKey, now: Long): MemoryConversationReceipt? = error("unused")
        override fun receipt(key: MemoryConversationRequestKey): MemoryConversationReceipt? = error("unused")
        override fun attachSession(key: MemoryConversationRequestKey, sessionId: Int, now: Long) = error("unused")
        override fun markAnswerReady(key: MemoryConversationRequestKey, answer: String, now: Long) = error("unused")
        override fun markCompleted(key: MemoryConversationRequestKey, deliveryId: String, now: Long) = error("unused")
        override fun markFailed(key: MemoryConversationRequestKey, now: Long) = error("unused")
        override fun createAndActivate(
            participant: MemoryConversationParticipant,
            conversationThreadId: String,
            now: Long,
        ): MemoryConversationSession = error("unused")
        override fun lease(
            participant: MemoryConversationParticipant,
            now: Long,
            idleTimeoutMillis: Long,
        ): MemoryConversationSessionLease = error("unused")
        override fun clearActive(participant: MemoryConversationParticipant) = error("unused")
        override fun touch(participant: MemoryConversationParticipant, sessionId: Int, now: Long) = error("unused")
        override fun failStaleProcessing(before: Long, now: Long): Int = error("unused")
    }

    private companion object {
        const val NOW = 1_000_000L
        const val TIMEOUT = HandleMemoryConversation.SESSION_IDLE_TIMEOUT_MILLIS

        fun session(id: Int, threadId: String, lastActiveAt: Long) = MemoryConversationSession(
            id = id,
            participant = MemoryConversationParticipant("scope", "participant-$id", UserId("user-$id")),
            conversationThreadId = threadId,
            createdAt = 0,
            lastActiveAt = lastActiveAt,
        )
    }
}
