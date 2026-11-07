package com.homeassistant.application.port.outbound.identity

import com.homeassistant.domain.identity.UserId

/** Stores login sessions by the hash of their id, so a leaked database cannot be replayed as a cookie. */
interface UserSessionRepository {
    /** The session whose id hash is [idHash] and whose expiry is after [now], or null. */
    fun findUnexpired(idHash: String, now: Long): StoredSession?

    /** Stores [session] and returns it. */
    fun save(session: StoredSession): StoredSession

    /** Removes the session whose id hash is [idHash]; an unknown hash removes nothing. */
    fun deleteByIdHash(idHash: String)

    /** Removes every session whose expiry is at or before [now]. */
    fun deleteExpiredBy(now: Long)
}

data class StoredSession(
    val idHash: String,
    val userId: UserId,
    val expiresAt: Long,
)
