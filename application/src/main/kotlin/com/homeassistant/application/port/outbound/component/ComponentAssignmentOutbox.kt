package com.homeassistant.application.port.outbound.component

data class ComponentAssignmentTask(
    val outboxId: Int,
    /** Monotonically increasing claim generation used to fence stale workers. */
    val attempt: Int,
    val episode: PendingEpisode,
)

/** Durable queue of episodes awaiting Component assignment, written in the same transaction as the episode. */
interface ComponentAssignmentOutbox {
    /**
     * Claims ready work and increments its attempt count. Stale in-progress work may be reclaimed;
     * failed work stays failed after [maxAttempts]. An episode that already belongs to a Component is
     * completed instead of returned, so a crash after the Component write never assigns it twice.
     */
    fun claimReady(
        limit: Int,
        now: Long,
        retryBefore: Long,
        staleProcessingBefore: Long,
        maxAttempts: Int,
    ): List<ComponentAssignmentTask>

    /** Returns true only when the matching in-progress generation was transitioned. */
    fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long): Boolean

    /** Returns true only when the matching in-progress generation was transitioned. */
    fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long): Boolean

    /** Queues episodes that belong to no Component and have no outbox row yet; returns how many were queued. */
    fun enqueueUnassigned(now: Long): Int
}
