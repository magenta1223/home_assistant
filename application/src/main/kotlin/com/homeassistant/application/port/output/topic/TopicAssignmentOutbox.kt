package com.homeassistant.application.port.output.topic

data class TopicAssignmentTask(
    val outboxId: Int,
    /** Monotonically increasing claim generation used to fence stale workers. */
    val attempt: Int,
    val memory: PendingMemory,
)

/** Durable queue of memories awaiting Topic assignment, written in the same transaction as the memory. */
interface TopicAssignmentOutbox {
    /**
     * Claims ready work and increments its attempt count. Stale in-progress work may be reclaimed;
     * failed work stays failed after [maxAttempts]. A memory that already belongs to a Topic is
     * completed instead of returned, so a crash after the Topic write never assigns it twice.
     */
    fun claimReady(
        limit: Int,
        now: Long,
        retryBefore: Long,
        staleProcessingBefore: Long,
        maxAttempts: Int,
    ): List<TopicAssignmentTask>

    /** Returns true only when the matching in-progress generation was transitioned. */
    fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long): Boolean

    /** Returns true only when the matching in-progress generation was transitioned. */
    fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long): Boolean

    /** Queues memories that belong to no Topic and have no outbox row yet; returns how many were queued. */
    fun enqueueUnassigned(now: Long): Int
}
