package com.homeassistant.application.port.outbound.chatgateway

import com.homeassistant.domain.identity.UserId

data class ChatSession(
    val id: Int,
    val userId: UserId,
    val chatId: ChatId,
    val createdAt: Long,
    val lastActiveAt: Long,
)

/** Starts [chatId] at [now] as [userId]'s active session, replacing the previous one. */
data class ChatSessionStart(val userId: UserId, val chatId: ChatId, val now: Long)

/** Marks session [sessionId] active at [now], while it is still [userId]'s active session. */
data class ChatSessionRenewal(val userId: UserId, val sessionId: Int, val now: Long)

/** Which active sessions stop being active. */
sealed interface ChatSessionDeactivation {
    /** Session [sessionId], while it is still [userId]'s active session. */
    data class OfSession(val userId: UserId, val sessionId: Int) : ChatSessionDeactivation

    /** Every active session last active at or before [beforeInclusive]. */
    data class IdleSince(val beforeInclusive: Long) : ChatSessionDeactivation
}

/** A [ChatSessionRenewal] of a session that is no longer its user's active session. */
class ChatSessionConflict(sessionId: Int) : IllegalStateException("chat session is no longer active: $sessionId")

/** Stores each user's model chats and which one is active. */
interface ChatSessionRepository {
    /** [userId]'s active session, or null. */
    fun findActiveByUser(userId: UserId): ChatSession?

    /** Applies [start] and returns the new active session. */
    fun save(start: ChatSessionStart): ChatSession

    /**
     * Applies [renewal] and returns the renewed session.
     * @throws ChatSessionConflict when the session is no longer its user's active session.
     */
    fun save(renewal: ChatSessionRenewal): ChatSession

    /** Applies [deactivation] and returns the sessions it deactivated; one already inactive is left out. */
    fun save(deactivation: ChatSessionDeactivation): List<ChatSession>
}
