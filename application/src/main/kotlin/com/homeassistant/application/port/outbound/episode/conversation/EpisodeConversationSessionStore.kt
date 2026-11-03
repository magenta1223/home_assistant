package com.homeassistant.application.port.outbound.episode.conversation

import com.homeassistant.application.port.inbound.episode.conversation.EpisodeConversationParticipant
import com.homeassistant.application.port.inbound.episode.conversation.EpisodeConversationRequestKey

enum class EpisodeConversationRequestStatus {
    PROCESSING,
    ANSWER_READY,
    /** Read compatibility for previously completed requests; HTTP writes ANSWER_READY. */
    COMPLETED,
    FAILED,
}

data class EpisodeConversationSession(
    val id: Int,
    val participant: EpisodeConversationParticipant,
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
    val key: EpisodeConversationRequestKey,
    val status: EpisodeConversationRequestStatus,
    val sessionId: Int? = null,
    val answerText: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Stores request receipts and each participant's active model conversation. */
interface EpisodeConversationSessionStore {
    fun claimRequest(key: EpisodeConversationRequestKey, now: Long): EpisodeConversationReceipt?
    fun receipt(key: EpisodeConversationRequestKey): EpisodeConversationReceipt?
    fun attachSession(key: EpisodeConversationRequestKey, sessionId: Int, now: Long)
    fun markAnswerReady(key: EpisodeConversationRequestKey, answer: String, now: Long)
    fun markFailed(key: EpisodeConversationRequestKey, now: Long)

    fun createAndActivate(
        participant: EpisodeConversationParticipant,
        conversationId: ConversationId,
        now: Long,
    ): EpisodeConversationSession

    /** Claims and renews this participant's active session, or removes and returns an expired one. */
    fun lease(
        participant: EpisodeConversationParticipant,
        now: Long,
        idleTimeoutMillis: Long,
    ): EpisodeConversationSessionLease

    fun clearActive(participant: EpisodeConversationParticipant)
    fun touch(participant: EpisodeConversationParticipant, sessionId: Int, now: Long)
    fun expireIdle(beforeInclusive: Long): List<EpisodeConversationSession>
    fun failStaleProcessing(before: Long, now: Long): Int
}
