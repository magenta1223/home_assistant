package com.homeassistant.application.usecase.memory.write

import com.homeassistant.application.port.output.memory.write.MemoryIndexingOutbox
import com.homeassistant.application.port.output.memory.write.SemanticMemoryIndexWriter
import java.time.Clock

data class MemoryIndexingRunResult(
    val completed: Int,
    val failed: Int,
)

/** Projects durable outbox work after the canonical database transaction has committed. */
class MemoryIndexingOutboxProcessor(
    private val outbox: MemoryIndexingOutbox,
    private val indexWriter: SemanticMemoryIndexWriter,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    private val processingLeaseMillis: Long = DEFAULT_PROCESSING_LEASE_MILLIS,
) {
    init {
        require(retryDelayMillis >= 0) { "retryDelayMillis must not be negative" }
        require(processingLeaseMillis > 0) { "processingLeaseMillis must be positive" }
    }

    fun processAvailable(limit: Int = DEFAULT_BATCH_SIZE): MemoryIndexingRunResult {
        require(limit > 0) { "limit must be positive" }
        val now = clock.millis()
        val tasks = outbox.claimReady(
            limit = limit,
            now = now,
            retryBefore = now - retryDelayMillis,
            staleProcessingBefore = now - processingLeaseMillis,
        )
        var completed = 0
        var failed = 0
        tasks.forEach { task ->
            val failure = runCatching {
                check(indexWriter.upsert(task.memory)) { "Semantic index writer rejected memory ${task.memory.id}" }
            }.exceptionOrNull()
            if (failure == null) {
                outbox.markCompleted(task.outboxId, clock.millis())
                completed++
            } else {
                outbox.markFailed(
                    task.outboxId,
                    failure.message ?: failure::class.simpleName ?: "Indexing failed",
                    clock.millis(),
                )
                failed++
            }
        }
        return MemoryIndexingRunResult(completed, failed)
    }

    /** Requeues and attempts every canonical memory, including memories missing an outbox row. */
    fun reindexAll(): MemoryIndexingRunResult {
        val queued = outbox.enqueueAll(clock.millis())
        if (queued == 0) return MemoryIndexingRunResult(0, 0)
        // Claim the freshly reset PENDING set together so a fast permanent failure cannot be
        // reclaimed and starve later memories during this explicit recovery run.
        return processAvailable(queued)
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 100
        const val DEFAULT_RETRY_DELAY_MILLIS = 30_000L
        const val DEFAULT_PROCESSING_LEASE_MILLIS = 5 * 60_000L
    }
}
