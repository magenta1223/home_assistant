package com.homeassistant.application.memory.save

import com.homeassistant.application.memory.index.SemanticMemoryIndexWriter
import com.homeassistant.application.memory.io.MemoryReader
import com.homeassistant.domain.memory.Memory
import org.slf4j.LoggerFactory

internal class MemoryIndexingCoordinator(
    private val memories: MemoryReader,
    private val indexWriter: SemanticMemoryIndexWriter,
    private val outbox: IndexingOutboxStore,
) {
    fun index(memory: Memory): Boolean = indexOne(memory)

    fun retryPending(currentMemoryIds: Set<Int>) {
        runCatching {
            val pending = outbox.pending(IndexTargetType.MEMORY).filterNot(currentMemoryIds::contains).toSet()
            memories.findByIds(pending).forEach { indexOne(it) }
        }.onFailure { error ->
            log.warn("Failed to dispatch pending memory indexes", error)
        }
    }

    private fun indexOne(memory: Memory): Boolean = try {
        indexWriter.upsert(memory)
        outbox.markIndexed(IndexTargetType.MEMORY, memory.id)
        true
    } catch (error: Exception) {
        log.warn("Memory vector indexing deferred memoryId=${memory.id}", error)
        runCatching {
            outbox.markFailed(
                IndexTargetType.MEMORY,
                memory.id,
                error.message ?: error::class.simpleName.orEmpty(),
            )
        }
        false
    }
}

private val log = LoggerFactory.getLogger(MemoryIndexingCoordinator::class.java)
