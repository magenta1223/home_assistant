package com.homeassistant.application.port.output.identity

import com.homeassistant.domain.identity.UserId

/** Stores login sessions by the hash of their id, so a leaked database cannot be replayed as a cookie. */
interface UserSessionStore {
    fun save(session: StoredSession)

    /** Returns the owner of a session whose expiry is after [now], or null. */
    fun findUser(idHash: String, now: Long): UserId?

    fun delete(idHash: String)

    fun deleteExpired(now: Long)
}

data class StoredSession(
    val idHash: String,
    val userId: UserId,
    val expiresAt: Long,
)
