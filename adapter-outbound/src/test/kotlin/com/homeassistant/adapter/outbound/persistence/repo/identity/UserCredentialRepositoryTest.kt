package com.homeassistant.adapter.outbound.persistence.repo.identity

import com.homeassistant.adapter.outbound.identity.Pbkdf2PasswordHasher
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.application.port.output.identity.PasswordHash
import com.homeassistant.application.port.output.identity.StoredCredential
import com.homeassistant.application.port.output.identity.StoredSession
import com.homeassistant.application.port.output.identity.UserRegistration
import com.homeassistant.domain.identity.Password
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserCredentialRepositoryTest {
    @Test
    fun `a display name can be registered once and is found with its hash`() = withStores { stores ->
        assertEquals(UserRegistration.REGISTERED, stores.userCredentials.register(FIRST, HASH))
        assertEquals(UserRegistration.REGISTERED, stores.userCredentials.register(SECOND, HASH))
        assertEquals(
            UserRegistration.NAME_TAKEN,
            stores.userCredentials.register(RegisteredUser(UserId("member-3"), SECOND.displayName), HASH),
        )

        assertEquals(listOf(FIRST, SECOND).sortedBy { it.displayName }, stores.users.list())
        assertTrue(stores.users.isRegistered(FIRST.userId))
        assertEquals(StoredCredential(SECOND.userId, HASH), stores.userCredentials.findByName(SECOND.displayName))
        assertNull(stores.userCredentials.findByName("없는사람"))
    }

    @Test
    fun `sessions resolve until they expire, and can be deleted`() = withStores { stores ->
        val sessions = stores.userSessions
        sessions.save(StoredSession("live", FIRST.userId, expiresAt = 200))
        sessions.save(StoredSession("expired", FIRST.userId, expiresAt = 100))

        assertEquals(FIRST.userId, sessions.findUser("live", now = 150))
        assertNull(sessions.findUser("expired", now = 150))
        assertNull(sessions.findUser("unknown", now = 150))

        sessions.deleteExpired(now = 150)
        sessions.save(StoredSession("expired", FIRST.userId, expiresAt = 300))
        sessions.delete("live")

        assertNull(sessions.findUser("live", now = 150))
        assertEquals(FIRST.userId, sessions.findUser("expired", now = 150))
    }

    @Test
    fun `password hashes are salted and verify only the same password`() {
        val hasher = Pbkdf2PasswordHasher(iterations = 1_000)
        val password = Password("correct-password")

        val hash = hasher.hash(password)

        assertTrue(hasher.matches(password, hash))
        assertFalse(hasher.matches(Password("wrong-password"), hash))
        assertNotEquals(hash, hasher.hash(password))
        assertFalse(hash.encoded.contains(password.value))
    }

    private fun withStores(block: (RepositoryStores) -> Unit) {
        val path = Files.createTempFile("user-credentials", ".db")
        try {
            block(RepositoryFactory.create(path.toString()))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private companion object {
        val FIRST = RegisteredUser(UserId("member-1"), "김동훈")
        val SECOND = RegisteredUser(UserId("member-2"), "홍승민")
        val HASH = PasswordHash("encoded-hash")
    }
}
