package com.homeassistant.application.usecase.identity

import com.homeassistant.application.port.input.identity.InvalidCredentialsException
import com.homeassistant.application.port.input.identity.LoginSession
import com.homeassistant.application.port.input.identity.SignupClosedException
import com.homeassistant.application.port.input.identity.UserAuthentication
import com.homeassistant.application.port.input.identity.UserNameTakenException
import com.homeassistant.application.port.output.identity.PasswordHasher
import com.homeassistant.application.port.output.identity.StoredSession
import com.homeassistant.application.port.output.identity.UserCredentialStore
import com.homeassistant.application.port.output.identity.UserRegistration
import com.homeassistant.application.port.output.identity.UserSessionStore
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
    private val sessions: UserSessionStore,
    private val hasher: PasswordHasher,
    private val clock: () -> Long = System::currentTimeMillis,
) : UserAuthentication {
    override fun isSignupOpen(): Boolean = !credentials.hasUsers()

    override fun signUpFirstUser(name: String, password: Password): RegisteredUser {
        val user = newUser(name)
        requireRegistered(credentials.registerFirst(user, hasher.hash(password)), name)
        return user
    }

    override fun addUser(name: String, password: Password): RegisteredUser {
        val user = newUser(name)
        requireRegistered(credentials.register(user, hasher.hash(password)), name)
        return user
    }

    override fun login(name: String, password: Password): LoginSession {
        val credential = credentials.findByName(name.trim())
            ?.takeIf { hasher.matches(password, it.passwordHash) }
            ?: throw InvalidCredentialsException()
        val now = clock()
        sessions.deleteExpired(now)
        val sessionId = newSessionId()
        sessions.save(StoredSession(hash(sessionId), credential.userId, now + SESSION_LIFETIME.toMillis()))
        return LoginSession(sessionId, SESSION_LIFETIME)
    }

    override fun logout(sessionId: String) = sessions.delete(hash(sessionId))

    override fun resolveSession(sessionId: String): UserId? = sessions.findUser(hash(sessionId), clock())

    private fun newUser(name: String) = RegisteredUser(UserId("member-${UUID.randomUUID()}"), name.trim())

    private fun requireRegistered(registration: UserRegistration, name: String) = when (registration) {
        UserRegistration.REGISTERED -> Unit
        UserRegistration.USERS_ALREADY_EXIST -> throw SignupClosedException()
        UserRegistration.NAME_TAKEN -> throw UserNameTakenException(name)
    }

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
