package com.homeassistant.application.port.output.slackconversation

import com.homeassistant.application.port.input.slackconversation.SlackPrincipal

data class SlackMessageKey(
    val channelId: String,
    val messageTs: String,
) {
    init {
        require(channelId.isNotBlank()) { "channelId is required" }
        require(messageTs.isNotBlank()) { "messageTs is required" }
    }
}

enum class SlackMessageReceiptStatus {
    PROCESSING,
    ANSWER_READY,
    COMPLETED,
    FAILED,
}

data class SlackConversationSession(
    val id: Int,
    val principal: SlackPrincipal,
    val conversationThreadId: String,
    val createdAt: Long,
    val lastActiveAt: Long,
)

data class SlackMessageReceipt(
    val key: SlackMessageKey,
    val status: SlackMessageReceiptStatus,
    val sessionId: Int? = null,
    val answerText: String? = null,
    val responseTs: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Stores Slack message receipts and each member's active conversation session. */
interface SlackConversationSessionStore {
    /** Claims a Slack message for processing, or returns null if it was already claimed. */
    fun claimMessage(key: SlackMessageKey, now: Long): SlackMessageReceipt?

    /** Returns the processing receipt for a Slack message, if one exists. */
    fun receipt(key: SlackMessageKey): SlackMessageReceipt?

    /** Associates a claimed message with the conversation session that will answer it. */
    fun attachSession(key: SlackMessageKey, sessionId: Int, now: Long)

    /** Stores the generated answer before attempting Slack delivery. */
    fun markAnswerReady(key: SlackMessageKey, answer: String, now: Long)

    /** Marks a message as completed after its answer was delivered to Slack. */
    fun markCompleted(key: SlackMessageKey, responseTs: String, now: Long)

    /** Marks a message as failed so that it can be retried or diagnosed. */
    fun markFailed(key: SlackMessageKey, now: Long)

    /** Creates and activates a new conversation session for a Slack principal. */
    fun createAndActivate(
        principal: SlackPrincipal,
        conversationThreadId: String,
        now: Long,
    ): SlackConversationSession

    /** Returns the principal's active session when it has not exceeded the idle timeout. */
    fun active(
        principal: SlackPrincipal,
        now: Long,
        idleTimeoutMillis: Long,
    ): SlackConversationSession?

    /** Ends the principal's active session from the application's perspective. */
    fun clearActive(principal: SlackPrincipal)

    /** Updates the activity time of an active session. */
    fun touch(principal: SlackPrincipal, sessionId: Int, now: Long)

    /** Fails messages left in processing beyond the allowed stale threshold. */
    fun failStaleProcessing(before: Long, now: Long): Int
}
