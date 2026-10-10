package com.homeassistant.adapter.outbound.persistence.repo.memoryconversation

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationRequestStatus
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionLease
import com.homeassistant.application.port.output.memory.conversation.ConversationId
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs

class MemoryConversationSessionRepositoryTest {
    @Test
    fun `HTTP sessions and receipts in the existing schema survive removal of the channel adapter`() {
        val path = Files.createTempFile("existing-http-conversation", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("""
                        CREATE TABLE slack_codex_sessions (
                            id INTEGER PRIMARY KEY AUTOINCREMENT, team_id TEXT NOT NULL,
                            slack_user_id TEXT NOT NULL, user_id TEXT NOT NULL,
                            codex_thread_id TEXT NOT NULL UNIQUE, created_at BIGINT NOT NULL,
                            last_active_at BIGINT NOT NULL)
                    """.trimIndent())
                    statement.executeUpdate("""
                        CREATE TABLE slack_codex_active_sessions (
                            team_id TEXT NOT NULL, slack_user_id TEXT NOT NULL, session_id INTEGER NOT NULL,
                            PRIMARY KEY (team_id, slack_user_id))
                    """.trimIndent())
                    statement.executeUpdate("""
                        CREATE TABLE slack_message_receipts (
                            channel_id TEXT NOT NULL, message_ts TEXT NOT NULL, session_id INTEGER,
                            status TEXT NOT NULL, answer_text TEXT, response_ts TEXT,
                            created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
                            PRIMARY KEY (channel_id, message_ts))
                    """.trimIndent())
                    statement.executeUpdate("INSERT INTO slack_codex_sessions VALUES (1, 'http', 'member-1', 'member-1', 'saved-thread', 100, 100)")
                    statement.executeUpdate("INSERT INTO slack_codex_active_sessions VALUES ('http', 'member-1', 1)")
                    statement.executeUpdate("INSERT INTO slack_message_receipts VALUES ('http:member-1', 'request-1', 1, 'ANSWER_READY', 'saved answer', NULL, 100, 100)")
                }
            }

            val store = RepositoryFactory.create(path.toString()).memoryConversationSessions
            val key = MemoryConversationRequestKey("http:member-1", "request-1")
            val participant = MemoryConversationParticipant("http", "member-1", UserId("member-1"))

            assertNull(store.claimRequest(key, 101))
            assertEquals("saved answer", store.receipt(key)?.answerText)
            val lease = assertIs<MemoryConversationSessionLease.Active>(store.lease(participant, 101, 600_000))
            assertEquals(ConversationId("saved-thread"), lease.session.conversationId)
            assertIs<MemoryConversationSessionLease.Expired>(store.lease(participant, 600_101, 600_000))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `persists request answers and active conversation state`() {
        val databasePath = Files.createTempFile("memory-conversation", ".db")
        try {
            val store = RepositoryFactory.create(databasePath.toString()).memoryConversationSessions

            assertNotNull(store.claimRequest(KEY, 100))
            assertNull(store.claimRequest(KEY, 101))
            val session = store.createAndActivate(PARTICIPANT, ConversationId("thread-1"), 102)
            store.attachSession(KEY, session.id, 103)
            store.markAnswerReady(KEY, "answer", 104)

            val ready = store.receipt(KEY)
            assertEquals(MemoryConversationRequestStatus.ANSWER_READY, ready?.status)
            assertEquals("answer", ready?.answerText)
            val lease = assertIs<MemoryConversationSessionLease.Active>(
                store.lease(PARTICIPANT, 105, 600_000),
            )
            assertEquals(ConversationId("thread-1"), lease.session.conversationId)

        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `expires active sessions at the ten minute boundary`() {
        val databasePath = Files.createTempFile("memory-conversation-expiry", ".db")
        try {
            val store = RepositoryFactory.create(databasePath.toString()).memoryConversationSessions
            store.createAndActivate(PARTICIPANT, ConversationId("expired-thread"), 100)

            val expired = store.expireIdle(100)

            assertEquals(listOf(ConversationId("expired-thread")), expired.map { it.conversationId })
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
            store.createAndActivate(PARTICIPANT, ConversationId("private-thread"), 100)
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
