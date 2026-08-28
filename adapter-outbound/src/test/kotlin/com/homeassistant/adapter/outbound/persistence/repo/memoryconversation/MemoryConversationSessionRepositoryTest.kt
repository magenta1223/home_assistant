package com.homeassistant.adapter.outbound.persistence.repo.memoryconversation

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationRequestStatus
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionLease
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs

class MemoryConversationSessionRepositoryTest {
    @Test
    fun `persists generic request delivery and active conversation state`() {
        val databasePath = Files.createTempFile("memory-conversation", ".db")
        try {
            val store = RepositoryFactory.create(databasePath.toString()).memoryConversationSessions

            assertNotNull(store.claimRequest(KEY, 100))
            assertNull(store.claimRequest(KEY, 101))
            val session = store.createAndActivate(PARTICIPANT, "thread-1", 102)
            store.attachSession(KEY, session.id, 103)
            store.markAnswerReady(KEY, "answer", 104)

            val ready = store.receipt(KEY)
            assertEquals(MemoryConversationRequestStatus.ANSWER_READY, ready?.status)
            assertEquals("answer", ready?.answerText)
            val lease = assertIs<MemoryConversationSessionLease.Active>(
                store.lease(PARTICIPANT, 105, 600_000),
            )
            assertEquals("thread-1", lease.session.conversationThreadId)

            store.markCompleted(KEY, "delivery-1", 106)

            val completed = store.receipt(KEY)
            assertEquals(MemoryConversationRequestStatus.COMPLETED, completed?.status)
            assertEquals("delivery-1", completed?.deliveryId)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `expires active sessions at the ten minute boundary`() {
        val databasePath = Files.createTempFile("memory-conversation-expiry", ".db")
        try {
            val store = RepositoryFactory.create(databasePath.toString()).memoryConversationSessions
            store.createAndActivate(PARTICIPANT, "expired-thread", 100)

            val expired = store.expireIdle(100)

            assertEquals(listOf("expired-thread"), expired.map { it.conversationThreadId })
            assertIs<MemoryConversationSessionLease.None>(store.lease(PARTICIPANT, 101, 600_000))
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `does not lease a thread to a different application user`() {
        val databasePath = Files.createTempFile("memory-conversation-owner", ".db")
        try {
            val store = RepositoryFactory.create(databasePath.toString()).memoryConversationSessions
            store.createAndActivate(PARTICIPANT, "private-thread", 100)
            val impostor = PARTICIPANT.copy(userId = UserId("different-user"))

            assertIs<MemoryConversationSessionLease.None>(store.lease(impostor, 101, 600_000))
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private companion object {
        val PARTICIPANT = MemoryConversationParticipant(
            scopeId = "scope-1",
            participantId = "participant-1",
            userId = UserId("member-1"),
        )
        val KEY = MemoryConversationRequestKey("stream-1", "request-1")
    }
}
