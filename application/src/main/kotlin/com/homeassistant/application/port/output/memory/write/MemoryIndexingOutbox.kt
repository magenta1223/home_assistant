package com.homeassistant.application.port.output.memory.write

import com.homeassistant.domain.memory.Memory

data class MemoryIndexingTask(
    val outboxId: Int,
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

    fun markCompleted(outboxId: Int, now: Long)

    fun markFailed(outboxId: Int, error: String, now: Long)

    /** Queues every canonical memory, resetting prior queue state, for disaster recovery/rebuilds. */
    fun enqueueAll(now: Long): Int
}
