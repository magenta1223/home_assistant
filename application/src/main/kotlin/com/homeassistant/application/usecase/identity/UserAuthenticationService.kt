package com.homeassistant.application.usecase.identity

import com.homeassistant.application.port.inbound.identity.InvalidCredentialsException
import com.homeassistant.application.port.inbound.identity.LoginSession
import com.homeassistant.application.port.inbound.identity.UserAuthentication
import com.homeassistant.application.port.inbound.identity.UserNameTakenException
import com.homeassistant.application.port.outbound.identity.PasswordHasher
import com.homeassistant.application.port.outbound.identity.StoredSession
import com.homeassistant.application.port.outbound.identity.UserCredentialStore
import com.homeassistant.application.port.outbound.identity.UserRegistration
import com.homeassistant.application.port.outbound.identity.UserSessionRepository
import com.homeassistant.domain.identity.Password
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** Orchestrates registration and login over the credential, session and hashing ports. */
class UserAuthenticationService(
    private val credentials: UserCredentialStore,
    private val sessions: UserSessionRepository,
    private val hasher: PasswordHasher,
    private val clock: () -> Long = System::currentTimeMillis,
) : UserAuthentication {
    override fun signUp(name: String, password: Password): RegisteredUser {
        val user = RegisteredUser(UserId("member-${UUID.randomUUID()}"), name.trim())
        return when (credentials.register(user, hasher.hash(password))) {
            UserRegistration.REGISTERED -> user
            UserRegistration.NAME_TAKEN -> throw UserNameTakenException(user.displayName)
        }
    }

    override fun login(name: String, password: Password): LoginSession {
        val credential = credentials.findByName(name.trim())
            ?.takeIf { hasher.matches(password, it.passwordHash) }
            ?: throw InvalidCredentialsException()
        val now = clock()
        sessions.deleteExpiredBy(now)
        val sessionId = newSessionId()
        sessions.save(StoredSession(hash(sessionId), credential.userId, now + SESSION_LIFETIME.toMillis()))
        return LoginSession(sessionId, SESSION_LIFETIME)
    }

    override fun logout(sessionId: String) = sessions.deleteByIdHash(hash(sessionId))

    override fun resolveSession(sessionId: String): UserId? = sessions.findUnexpired(hash(sessionId), clock())?.userId

    private fun newSessionId(): String {
        val bytes = ByteArray(SESSION_ID_BYTES).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(sessionId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(sessionId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val SESSION_LIFETIME: Duration = Duration.ofDays(90)
        const val SESSION_ID_BYTES = 32
    }
}
