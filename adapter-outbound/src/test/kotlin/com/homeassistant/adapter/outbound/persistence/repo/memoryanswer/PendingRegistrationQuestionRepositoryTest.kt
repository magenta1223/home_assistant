package com.homeassistant.adapter.outbound.persistence.repo.memoryanswer

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.port.input.memory.answer.ConversationRequestKey
import com.homeassistant.application.port.input.identity.ConversationIdentity
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingRegistrationQuestionRepositoryTest {
    @Test
    fun `legacy pending question table is renamed without losing the question`() {
        val path = Files.createTempFile("legacy-pending-question", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "CREATE TABLE pending_household_conversations (" +
                            "scope_id TEXT, participant_id TEXT, stream_id TEXT, request_id TEXT, " +
                            "question TEXT, created_at INTEGER, PRIMARY KEY (scope_id, participant_id))",
                    )
                    statement.executeUpdate(
                        "INSERT INTO pending_household_conversations VALUES " +
                            "('scope-1', 'participant-1', 'stream-1', 'request-1', 'first', 100)",
                    )
                }
            }

            val store = RepositoryFactory.create(path.toString()).pendingRegistrationQuestions

            assertEquals(FIRST, store.find(IDENTITY))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `persists only the first pending question until registration completes`() {
        val path = Files.createTempFile("pending-registration-question", ".db")
        try {
            val store = RepositoryFactory.create(path.toString()).pendingRegistrationQuestions

            assertTrue(store.rememberFirst(FIRST, 100L))
            assertFalse(store.rememberFirst(SECOND, 101L))
            assertEquals(FIRST, store.find(IDENTITY))

            val reopened = RepositoryFactory.create(path.toString()).pendingRegistrationQuestions
            assertEquals(FIRST, reopened.find(IDENTITY))

            reopened.remove(IDENTITY)
            assertNull(reopened.find(IDENTITY))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private companion object {
        val IDENTITY = ConversationIdentity("scope-1", "participant-1")
        val FIRST = MemoryAnswerRequest(
            identity = IDENTITY,
            key = ConversationRequestKey("stream-1", "request-1"),
            question = "first",
        )
        val SECOND = MemoryAnswerRequest(
            identity = IDENTITY,
            key = ConversationRequestKey("stream-1", "request-2"),
            question = "second",
        )
    }
}
