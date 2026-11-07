package com.homeassistant.application.port.outbound.chatgateway

import com.homeassistant.domain.identity.UserId

data class ChatSession(
    val id: Int,
    val userId: UserId,
    val chatId: ChatId,
    val createdAt: Long,
    val lastActiveAt: Long,
)

sealed interface ChatSessionLease {
    data object None : ChatSessionLease
    data class Active(val session: ChatSession) : ChatSessionLease
    data class Expired(val session: ChatSession) : ChatSessionLease
}

/** Stores each user's active model chat. */
interface ChatSessionStore {
    fun createAndActivate(
        userId: UserId,
        chatId: ChatId,
        now: Long,
    ): ChatSession

    /** Claims and renews this user's active session, or removes and returns an expired one. */
    fun lease(
        userId: UserId,
        now: Long,
        idleTimeoutMillis: Long,
    ): ChatSessionLease

    fun clearActive(userId: UserId)
    fun touch(userId: UserId, sessionId: Int, now: Long)
    fun expireIdle(beforeInclusive: Long): List<ChatSession>
}
