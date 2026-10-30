package com.homeassistant.application.port.input.identity

import com.homeassistant.domain.identity.Password
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import java.time.Duration

/** Registers people by name and password and keeps them logged in with sessions. */
interface UserAuthentication {
    /**
     * Registers a new user.
     *
     * @throws UserNameTakenException when the name is in use
     */
    fun signUp(name: String, password: Password): RegisteredUser

    /**
     * Starts a session for the user with this name and password.
     *
     * @throws InvalidCredentialsException when they do not match
     */
    fun login(name: String, password: Password): LoginSession

    /** Ends the session. An unknown session is already ended. */
    fun logout(sessionId: String)

    /** Returns the user of a session that has not expired, or null when there is none. */
    fun resolveSession(sessionId: String): UserId?

    companion object {
        val NONE: UserAuthentication = object : UserAuthentication {
            override fun signUp(name: String, password: Password): RegisteredUser =
                throw UserNameTakenException(name)

            override fun login(name: String, password: Password): LoginSession =
                throw InvalidCredentialsException()

            override fun logout(sessionId: String) = Unit

            override fun resolveSession(sessionId: String): UserId? = null
        }
    }
}

/** A started session. [id] is the secret the client presents; only its hash is stored. */
data class LoginSession(
    val id: String,
    val lifetime: Duration,
)

class UserNameTakenException internal constructor(
    name: String,
) : RuntimeException("user name is already in use: $name")

class InvalidCredentialsException internal constructor() : RuntimeException("name or password is incorrect")
