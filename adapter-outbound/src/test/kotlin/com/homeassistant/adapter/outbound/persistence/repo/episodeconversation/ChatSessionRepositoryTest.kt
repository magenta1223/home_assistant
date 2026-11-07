package com.homeassistant.adapter.outbound.persistence.repo.episodeconversation

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionConflict
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionDeactivation
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionRenewal
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionRepository
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionStart
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChatSessionRepositoryTest {
    @Test
    fun `a started session is its user's active session until another replaces it`() = withSessions { sessions ->
        val first = sessions.save(ChatSessionStart(USER_ID, ChatId("thread-1"), 100))
        assertEquals(first, sessions.findActiveByUser(USER_ID))
        assertNull(sessions.findActiveByUser(UserId("different-user")))

        val second = sessions.save(ChatSessionStart(USER_ID, ChatId("thread-2"), 200))

        assertEquals(second, sessions.findActiveByUser(USER_ID))
        assertFailsWith<ChatSessionConflict> { sessions.save(ChatSessionRenewal(USER_ID, first.id, 300)) }
    }

    @Test
    fun `renewing the active session moves its last activity`() = withSessions { sessions ->
        val session = sessions.save(ChatSessionStart(USER_ID, ChatId("thread-1"), 100))

        val renewed = sessions.save(ChatSessionRenewal(USER_ID, session.id, 150))

        assertEquals(session.copy(lastActiveAt = 150), renewed)
        assertEquals(renewed, sessions.findActiveByUser(USER_ID))
        assertFailsWith<ChatSessionConflict> {
            sessions.save(ChatSessionRenewal(UserId("different-user"), session.id, 160))
        }
    }

    @Test
    fun `deactivates only the named session while it is active`() = withSessions { sessions ->
        val session = sessions.save(ChatSessionStart(USER_ID, ChatId("thread-1"), 100))

        assertEquals(emptyList(), sessions.save(ChatSessionDeactivation.OfSession(UserId("different-user"), session.id)))
        assertEquals(listOf(session), sessions.save(ChatSessionDeactivation.OfSession(USER_ID, session.id)))
        assertEquals(emptyList(), sessions.save(ChatSessionDeactivation.OfSession(USER_ID, session.id)))
        assertNull(sessions.findActiveByUser(USER_ID))
    }

    @Test
    fun `deactivates sessions idle at the boundary`() = withSessions { sessions ->
        val idle = sessions.save(ChatSessionStart(USER_ID, ChatId("idle-thread"), 100))
        val recent = sessions.save(ChatSessionStart(UserId("member-2"), ChatId("recent-thread"), 101))

        assertEquals(listOf(idle), sessions.save(ChatSessionDeactivation.IdleSince(100)))
        assertNull(sessions.findActiveByUser(USER_ID))
        assertEquals(recent, sessions.findActiveByUser(UserId("member-2")))
    }

    private fun withSessions(block: (ChatSessionRepository) -> Unit) {
        val path = Files.createTempFile("chat-sessions", ".db")
        try {
            block(RepositoryFactory.create(path.toString()).chatSessions)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private companion object {
        val USER_ID = UserId("member-1")
    }
}
