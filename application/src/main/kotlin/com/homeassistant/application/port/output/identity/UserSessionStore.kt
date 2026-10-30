package com.homeassistant.application.port.output.identity

import com.homeassistant.domain.identity.UserId

/** Stores login sessions by the hash of their id, so a leaked database cannot be replayed as a cookie. */
interface UserSessionStore {
    /** Stores a new session. */
    fun save(session: StoredSession)

    /** Returns the owner of a session whose expiry is after [now], or null. */
    fun findUser(idHash: String, now: Long): UserId?

    /** Removes the session with [idHash]; removing an unknown session does nothing. */
    fun delete(idHash: String)

    /** Removes every session whose expiry is at or before [now]. */
    fun deleteExpired(now: Long)
}

data class StoredSession(
    val idHash: String,
    val userId: UserId,
    val expiresAt: Long,
)
