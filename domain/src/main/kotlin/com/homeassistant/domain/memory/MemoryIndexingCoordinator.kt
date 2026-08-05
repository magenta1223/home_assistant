package com.homeassistant.domain.memory

import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.domain.indexing.IndexingOutboxStore
import org.slf4j.LoggerFactory

internal interface MemoryIndexingCoordinator {
    fun index(memory: MemoryRow): Boolean
    fun retryPending(currentMemoryId: Int)
}

internal object MemoryIndexingCoordinatorFactory {
    fun create(
        memoryStore: MemoryStore,
        embeddingService: EmbeddingService,
        vectorStore: VectorStore,
        indexingOutbox: IndexingOutboxStore,
    ): MemoryIndexingCoordinator =
        DefaultMemoryIndexingCoordinator(
            memoryStore,
            embeddingService,
            vectorStore,
            indexingOutbox,
        )
}

private class DefaultMemoryIndexingCoordinator(
    private val memoryStore: MemoryStore,
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
    private val indexingOutbox: IndexingOutboxStore,
) : MemoryIndexingCoordinator {
    override fun index(memory: MemoryRow): Boolean =
        try {
            vectorStore.upsert(memory.toVectorPoint())
            indexingOutbox.markIndexed(IndexTargetType.MEMORY, memory.id)
            true
        } catch (error: Exception) {
            log.warn("Memory vector indexing deferred memoryId=${memory.id}", error)
            runCatching {
                indexingOutbox.markFailed(
                    IndexTargetType.MEMORY,
                    memory.id,
                    error.message ?: error::class.simpleName.orEmpty(),
                )
            }
            false
        }

    override fun retryPending(currentMemoryId: Int) {
        runCatching {
            indexingOutbox.pending(IndexTargetType.MEMORY)
                .asSequence()
                .filter { it != currentMemoryId }
                .mapNotNull(memoryStore::getMemory)
                .forEach(::index)
        }.onFailure { error ->
            log.warn("Failed to dispatch pending memory indexes", error)
        }
    }

    private fun MemoryRow.toVectorPoint() =
        VectorPoint(
            memoryId = id,
            vector = embeddingService.embed("passage: $summary\n$content"),
            payload = mapOf(
                "familyId" to familyId,
                "memoryId" to id.toString(),
                "memoryType" to memoryType.code,
                "domain" to domainName,
                "memberId" to (subjectMemberId ?: ""),
                "createdBy" to createdBy,
            ),
            numericPayload = mapOf("createdAt" to createdAt),
        )
}

private val log = LoggerFactory.getLogger(MemoryIndexingCoordinator::class.java)
