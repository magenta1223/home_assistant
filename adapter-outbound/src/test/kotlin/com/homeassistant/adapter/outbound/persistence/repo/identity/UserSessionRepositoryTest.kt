package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.port.outbound.identity.StoredSession
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserSessionRepositoryTest {
    @Test
    fun `sessions are found until they expire, and can be deleted`() {
        val path = Files.createTempFile("user-sessions", ".db")
        try {
            val sessions = RepositoryFactory.create(path.toString()).userSessions
            val live = StoredSession("live", USER, expiresAt = 200)
            assertEquals(live, sessions.save(live))
            sessions.save(StoredSession("expired", USER, expiresAt = 100))

            assertEquals(live, sessions.findUnexpired("live", now = 150))
            assertNull(sessions.findUnexpired("expired", now = 150))
            assertNull(sessions.findUnexpired("unknown", now = 150))

            sessions.deleteExpiredBy(now = 150)
            val renewed = sessions.save(StoredSession("expired", USER, expiresAt = 300))
            sessions.deleteByIdHash("live")

            assertNull(sessions.findUnexpired("live", now = 150))
            assertEquals(renewed, sessions.findUnexpired("expired", now = 150))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private companion object {
        val USER = UserId("member-1")
    }
}
