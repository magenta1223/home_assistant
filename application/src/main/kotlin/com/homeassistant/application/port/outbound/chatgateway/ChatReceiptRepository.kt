package com.homeassistant.application.port.outbound.chatgateway

import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey

enum class ChatRequestStatus {
    PROCESSING,
    ANSWER_READY,
    FAILED,
}

data class ChatReceipt(
    val key: ChatRequestKey,
    val status: ChatRequestStatus,
    val sessionId: Int? = null,
    val answerText: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A change to the receipt of one request, made at [now]. */
sealed interface ChatReceiptChange {
    val key: ChatRequestKey
    val now: Long

    /** Records a new request as processing. */
    data class Claim(override val key: ChatRequestKey, override val now: Long) : ChatReceiptChange

    /** Records the chat session the request is answered in. */
    data class AttachSession(
        override val key: ChatRequestKey,
        val sessionId: Int,
        override val now: Long,
    ) : ChatReceiptChange

    /** Records the answer, so a retried request gets it again. */
    data class AnswerReady(
        override val key: ChatRequestKey,
        val answer: String,
        override val now: Long,
    ) : ChatReceiptChange {
        init { require(answer.isNotBlank()) { "answer is required" } }
    }

    /** Records that the request was not answered. */
    data class Fail(override val key: ChatRequestKey, override val now: Long) : ChatReceiptChange
}

/** Fails, at [now], every receipt still processing since [before]: a previous run left it behind. */
data class StaleProcessingFailure(val before: Long, val now: Long)

/** A [ChatReceiptChange.Claim] for a request that already has a receipt. */
class ChatReceiptConflict(key: ChatRequestKey) :
    IllegalStateException("request is already claimed: ${key.requestId}")

/** Stores one receipt per request, so a retried request is answered once. */
interface ChatReceiptRepository {
    /** The receipt of the request with [key], or null. */
    fun findByKey(key: ChatRequestKey): ChatReceipt?

    /**
     * Applies [change] and returns the resulting receipt.
     * @throws ChatReceiptConflict when [change] claims a request that already has a receipt.
     */
    fun save(change: ChatReceiptChange): ChatReceipt

    /** Applies [failure] and returns the receipts it failed. */
    fun save(failure: StaleProcessingFailure): List<ChatReceipt>
}
