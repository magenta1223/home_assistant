package com.homeassistant.application.port.output.memory.write

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryProposal

/** One proposed memory together with its stable retry identity. */
data class IdempotentMemoryProposal(
    val idempotencyKey: String,
    val proposal: MemoryProposal,
)

/**
 * Atomically commits one completed source-analysis batch.
 *
 * Implementations must persist memories, evidence, indexing outbox entries, and the source-record
 * status change in one transaction. External indexing must not happen inside this call.
 */
fun interface CanonicalMemoryBatchWriter {
    fun commit(
        createdBy: UserId,
        proposals: List<IdempotentMemoryProposal>,
        analyzedSourceRecordIds: Collection<Int>,
    ): List<Memory>
}
