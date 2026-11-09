package com.homeassistant.application.usecase.contexttool

import com.homeassistant.application.port.inbound.contexttool.ContextAccessExpiredException
import com.homeassistant.domain.identity.UserId
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Issues the short-lived tokens an agent presents to read context. A token stands for the user it was issued for, so
 * the agent never names a user itself; it lasts at most [lifetime] and is revoked when the chat turn ends. Tokens live
 * only in this process: a token outlives no turn, so a restart only ends the turns it interrupts anyway.
 */
class ContextGrants(
    private val lifetime: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val grants = ConcurrentHashMap<String, Grant>()

    /** A new token that reads [userId]'s context until it is revoked or [lifetime] passes. */
    fun issue(userId: UserId): String {
        val now = clock.millis()
        grants.values.removeIf { it.isExpiredAt(now) }
        val token = newToken()
        grants[token] = Grant(userId, now + lifetime.toMillis())
        return token
    }

    /** Ends [token]; reading with it fails afterwards. */
    fun revoke(token: String) {
        grants.remove(token)
    }

    /**
     * The user [token] was issued for.
     *
     * @throws ContextAccessExpiredException when [token] is unknown, revoked or expired.
     */
    fun ownerOf(token: String): UserId =
        grants[token]?.takeUnless { it.isExpiredAt(clock.millis()) }?.userId ?: throw ContextAccessExpiredException()

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Lets the bearer of a token read [userId]'s context until [expiresAt]. */
    private data class Grant(val userId: UserId, val expiresAt: Long) {
        fun isExpiredAt(now: Long): Boolean = expiresAt <= now
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
