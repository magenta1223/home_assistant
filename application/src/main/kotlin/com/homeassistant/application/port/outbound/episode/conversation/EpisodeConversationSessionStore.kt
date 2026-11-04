package com.homeassistant.application.port.outbound.episode.conversation

import com.homeassistant.application.port.inbound.chatservice.ChatParticipant
import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey

enum class EpisodeConversationRequestStatus {
    PROCESSING,
    ANSWER_READY,
    /** Read compatibility for previously completed requests; HTTP writes ANSWER_READY. */
    COMPLETED,
    FAILED,
}

data class EpisodeConversationSession(
    val id: Int,
    val participant: ChatParticipant,
    val chatId: ChatId,
    val createdAt: Long,
    val lastActiveAt: Long,
)

sealed interface EpisodeConversationSessionLease {
    data object None : EpisodeConversationSessionLease
    data class Active(val session: EpisodeConversationSession) : EpisodeConversationSessionLease
    data class Expired(val session: EpisodeConversationSession) : EpisodeConversationSessionLease
}

data class EpisodeConversationReceipt(
    val key: ChatRequestKey,
    val status: EpisodeConversationRequestStatus,
    val sessionId: Int? = null,
    val answerText: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Stores request receipts and each participant's active model conversation. */
interface EpisodeConversationSessionStore {
    fun claimRequest(key: ChatRequestKey, now: Long): EpisodeConversationReceipt?
    fun receipt(key: ChatRequestKey): EpisodeConversationReceipt?
    fun attachSession(key: ChatRequestKey, sessionId: Int, now: Long)
    fun markAnswerReady(key: ChatRequestKey, answer: String, now: Long)
    fun markFailed(key: ChatRequestKey, now: Long)

    fun createAndActivate(
        participant: ChatParticipant,
        chatId: ChatId,
        now: Long,
    ): EpisodeConversationSession

    /** Claims and renews this participant's active session, or removes and returns an expired one. */
    fun lease(
        participant: ChatParticipant,
        now: Long,
        idleTimeoutMillis: Long,
    ): EpisodeConversationSessionLease

    fun clearActive(participant: ChatParticipant)
    fun touch(participant: ChatParticipant, sessionId: Int, now: Long)
    fun expireIdle(beforeInclusive: Long): List<EpisodeConversationSession>
    fun failStaleProcessing(before: Long, now: Long): Int
}
