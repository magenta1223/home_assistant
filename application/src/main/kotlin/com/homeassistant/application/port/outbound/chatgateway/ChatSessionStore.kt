package com.homeassistant.application.port.outbound.chatgateway

import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey
import com.homeassistant.domain.identity.UserId

enum class ChatRequestStatus {
    PROCESSING,
    ANSWER_READY,
    /** Read compatibility for previously completed requests; HTTP writes ANSWER_READY. */
    COMPLETED,
    FAILED,
}

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

data class ChatReceipt(
    val key: ChatRequestKey,
    val status: ChatRequestStatus,
    val sessionId: Int? = null,
    val answerText: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Stores request receipts and each user's active model chat. */
interface ChatSessionStore {
    fun claimRequest(key: ChatRequestKey, now: Long): ChatReceipt?
    fun receipt(key: ChatRequestKey): ChatReceipt?
    fun attachSession(key: ChatRequestKey, sessionId: Int, now: Long)
    fun markAnswerReady(key: ChatRequestKey, answer: String, now: Long)
    fun markFailed(key: ChatRequestKey, now: Long)

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
    fun failStaleProcessing(before: Long, now: Long): Int
}
