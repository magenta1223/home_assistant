package com.homeassistant.application.port.output.memory.write

import com.homeassistant.domain.memory.Memory

data class MemoryIndexingTask(
    val outboxId: Int,
    /** Monotonically increasing claim generation used to fence stale workers. */
    val attempt: Int,
    val memory: Memory,
)

/** Durable queue for projecting canonical memories into semantic search. */
interface MemoryIndexingOutbox {
    /** Claims ready work and increments its attempt count. Stale in-progress work may be reclaimed. */
    fun claimReady(
        limit: Int,
        now: Long,
        retryBefore: Long,
        staleProcessingBefore: Long,
    ): List<MemoryIndexingTask>

    /** Returns true only when the matching in-progress generation was transitioned. */
    fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long): Boolean

    /** Returns true only when the matching in-progress generation was transitioned. */
    fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long): Boolean

    /** Queues every canonical memory without resetting its claim generation. */
    fun enqueueAll(now: Long): Int
}
