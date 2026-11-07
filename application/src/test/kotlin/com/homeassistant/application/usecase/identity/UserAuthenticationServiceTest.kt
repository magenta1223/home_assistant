package com.homeassistant.application.usecase.identity

import com.homeassistant.application.port.inbound.identity.InvalidCredentialsException
import com.homeassistant.application.port.inbound.identity.UserNameTakenException
import com.homeassistant.application.port.outbound.identity.PasswordHash
import com.homeassistant.application.port.outbound.identity.PasswordHasher
import com.homeassistant.application.port.outbound.identity.StoredCredential
import com.homeassistant.application.port.outbound.identity.StoredSession
import com.homeassistant.application.port.outbound.identity.UserNameConflict
import com.homeassistant.application.port.outbound.identity.UserRegistration
import com.homeassistant.application.port.outbound.identity.UserRepository
import com.homeassistant.application.port.outbound.identity.UserSessionRepository
import com.homeassistant.domain.identity.Password
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserAuthenticationServiceTest {
    private val users = InMemoryUsers()
    private val sessions = InMemorySessions()
    private var now = 1_000L
    private val service = UserAuthenticationService(users, sessions, PlainHasher) { now }

    @Test
    fun `anyone signs up with a free name and can then log in`() {
        val first = service.signUp(" 김동훈 ", PASSWORD)
        val second = service.signUp("홍승민", PASSWORD)

        assertEquals("김동훈", first.displayName)
        assertTrue(first.userId.value.startsWith("member-"))
        assertEquals(second.userId, service.resolveSession(service.login("홍승민", PASSWORD).id))
    }

    @Test
    fun `a name is used only once`() {
        service.signUp("김동훈", PASSWORD)

        assertFailsWith<UserNameTakenException> { service.signUp("김동훈", PASSWORD) }
    }

    @Test
    fun `login rejects a wrong password and an unknown name`() {
        service.signUp("김동훈", PASSWORD)

        assertFailsWith<InvalidCredentialsException> { service.login("김동훈", Password("wrong-password")) }
        assertFailsWith<InvalidCredentialsException> { service.login("없는사람", PASSWORD) }
    }

    @Test
    fun `sessions are stored only as hashes`() {
        service.signUp("김동훈", PASSWORD)

        val session = service.login("김동훈", PASSWORD)

        assertNotEquals(session.id, sessions.stored.keys.single())
    }

    @Test
    fun `a session stops working at its expiry and is removed by a later login`() {
        val user = service.signUp("김동훈", PASSWORD)
        val session = service.login("김동훈", PASSWORD)

        assertEquals(user.userId, service.resolveSession(session.id))

        now += Duration.ofDays(90).toMillis()
        assertNull(service.resolveSession(session.id))

        service.login("김동훈", PASSWORD)
        assertEquals(1, sessions.stored.size)
    }

    @Test
    fun `logout ends only that session`() {
        val user = service.signUp("김동훈", PASSWORD)
        val phone = service.login("김동훈", PASSWORD)
        val laptop = service.login("김동훈", PASSWORD)

        service.logout(phone.id)

        assertNull(service.resolveSession(phone.id))
        assertEquals(user.userId, service.resolveSession(laptop.id))
    }

    private object PlainHasher : PasswordHasher {
        override fun hash(password: Password) = PasswordHash("hashed:${password.value}")

        override fun matches(password: Password, hash: PasswordHash) = hash == hash(password)
    }

    private class InMemoryUsers : UserRepository {
        private val registrations = mutableMapOf<String, UserRegistration>()

        override fun findAll() = registrations.values.map { it.user }

        override fun findById(userId: UserId) = registrations.values.map { it.user }.find { it.userId == userId }

        override fun findCredentialByName(name: String) =
            registrations[name]?.let { StoredCredential(it.user.userId, it.passwordHash) }

        override fun save(registration: UserRegistration): RegisteredUser {
            val name = registration.user.displayName
            if (name in registrations) throw UserNameConflict(name)
            registrations[name] = registration
            return registration.user
        }
    }

    private class InMemorySessions : UserSessionRepository {
        val stored = mutableMapOf<String, StoredSession>()

        override fun findUnexpired(idHash: String, now: Long) = stored[idHash]?.takeIf { it.expiresAt > now }

        override fun save(session: StoredSession): StoredSession {
            stored[session.idHash] = session
            return session
        }

        override fun deleteByIdHash(idHash: String) {
            stored.remove(idHash)
        }

        override fun deleteExpiredBy(now: Long) {
            stored.values.removeIf { it.expiresAt <= now }
        }
    }

    private companion object {
        val PASSWORD = Password("correct-password")
    }
}
