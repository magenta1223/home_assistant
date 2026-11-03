package com.homeassistant.application.port.outbound.episode.conversation

import com.homeassistant.application.port.inbound.episode.ConversationParticipant
import com.homeassistant.application.port.inbound.episode.ConversationRequestKey

enum class EpisodeConversationRequestStatus {
    PROCESSING,
    ANSWER_READY,
    /** Read compatibility for previously completed requests; HTTP writes ANSWER_READY. */
    COMPLETED,
    FAILED,
}

data class EpisodeConversationSession(
    val id: Int,
    val participant: ConversationParticipant,
    val conversationId: ConversationId,
    val createdAt: Long,
    val lastActiveAt: Long,
)

sealed interface EpisodeConversationSessionLease {
    data object None : EpisodeConversationSessionLease
    data class Active(val session: EpisodeConversationSession) : EpisodeConversationSessionLease
    data class Expired(val session: EpisodeConversationSession) : EpisodeConversationSessionLease
}

data class EpisodeConversationReceipt(
    val key: ConversationRequestKey,
    val status: EpisodeConversationRequestStatus,
    val sessionId: Int? = null,
    val answerText: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Stores request receipts and each participant's active model conversation. */
interface EpisodeConversationSessionStore {
    fun claimRequest(key: ConversationRequestKey, now: Long): EpisodeConversationReceipt?
    fun receipt(key: ConversationRequestKey): EpisodeConversationReceipt?
    fun attachSession(key: ConversationRequestKey, sessionId: Int, now: Long)
    fun markAnswerReady(key: ConversationRequestKey, answer: String, now: Long)
    fun markFailed(key: ConversationRequestKey, now: Long)

    fun createAndActivate(
        participant: ConversationParticipant,
        conversationId: ConversationId,
        now: Long,
    ): EpisodeConversationSession

    /** Claims and renews this participant's active session, or removes and returns an expired one. */
    fun lease(
        participant: ConversationParticipant,
        now: Long,
        idleTimeoutMillis: Long,
    ): EpisodeConversationSessionLease

    fun clearActive(participant: ConversationParticipant)
    fun touch(participant: ConversationParticipant, sessionId: Int, now: Long)
    fun expireIdle(beforeInclusive: Long): List<EpisodeConversationSession>
    fun failStaleProcessing(before: Long, now: Long): Int
}
