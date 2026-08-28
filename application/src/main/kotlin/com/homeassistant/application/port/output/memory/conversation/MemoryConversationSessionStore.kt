package com.homeassistant.application.port.output.memory.conversation

import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey

enum class MemoryConversationRequestStatus {
    PROCESSING,
    ANSWER_READY,
    COMPLETED,
    FAILED,
}

data class MemoryConversationSession(
    val id: Int,
    val participant: MemoryConversationParticipant,
    val conversationThreadId: String,
    val createdAt: Long,
    val lastActiveAt: Long,
)

sealed interface MemoryConversationSessionLease {
    data object None : MemoryConversationSessionLease
    data class Active(val session: MemoryConversationSession) : MemoryConversationSessionLease
    data class Expired(val session: MemoryConversationSession) : MemoryConversationSessionLease
}

data class MemoryConversationReceipt(
    val key: MemoryConversationRequestKey,
    val status: MemoryConversationRequestStatus,
    val sessionId: Int? = null,
    val answerText: String? = null,
    val deliveryId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Stores request receipts and each participant's active model conversation. */
interface MemoryConversationSessionStore {
    fun claimRequest(key: MemoryConversationRequestKey, now: Long): MemoryConversationReceipt?
    fun receipt(key: MemoryConversationRequestKey): MemoryConversationReceipt?
    fun attachSession(key: MemoryConversationRequestKey, sessionId: Int, now: Long)
    fun markAnswerReady(key: MemoryConversationRequestKey, answer: String, now: Long)
    fun markCompleted(key: MemoryConversationRequestKey, deliveryId: String, now: Long)
    fun markFailed(key: MemoryConversationRequestKey, now: Long)

    fun createAndActivate(
        participant: MemoryConversationParticipant,
        conversationThreadId: String,
        now: Long,
    ): MemoryConversationSession

    /** Claims and renews this participant's active session, or removes and returns an expired one. */
    fun lease(
        participant: MemoryConversationParticipant,
        now: Long,
        idleTimeoutMillis: Long,
    ): MemoryConversationSessionLease

    fun clearActive(participant: MemoryConversationParticipant)
    fun touch(participant: MemoryConversationParticipant, sessionId: Int, now: Long)
    fun expireIdle(beforeInclusive: Long): List<MemoryConversationSession>
    fun failStaleProcessing(before: Long, now: Long): Int
}
