package com.homeassistant.adapter.outbound.persistence.repo.memoryconversation

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationRequestStatus
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
            assertEquals("thread-1", store.active(PARTICIPANT, 105, 600_000)?.conversationThreadId)

            store.markCompleted(KEY, "delivery-1", 106)

            val completed = store.receipt(KEY)
            assertEquals(MemoryConversationRequestStatus.COMPLETED, completed?.status)
            assertEquals("delivery-1", completed?.deliveryId)
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
