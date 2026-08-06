package com.homeassistant.application.topicanalysis.save

import com.homeassistant.application.memory.CanonicalMemoryContext
import com.homeassistant.application.memory.MemoryTopicContext
import com.homeassistant.application.memory.index.MemoryIndexer
import com.homeassistant.application.memory.index.MemoryIndexingSource
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.topicanalysis.Topic
import org.slf4j.LoggerFactory

internal class MemoryIndexingCoordinator(
    private val memories: MemoryIndexingSource,
    private val indexer: MemoryIndexer,
    private val outbox: IndexingOutboxStore,
) {
    fun index(topic: Topic): Boolean =
        topic.memories.map { memory ->
            index(
                CanonicalMemoryContext(
                    memory,
                    MemoryTopicContext(
                        topic.id,
                        topic.title,
                        topic.summary,
                        SourceDescriptor(topic.sourceType, topic.sourceName),
                    ),
                ),
            )
        }.all { it }

    fun retryPending(currentMemoryIds: Set<Int>) {
        runCatching {
            val pending = outbox.pending(IndexTargetType.MEMORY).filterNot(currentMemoryIds::contains).toSet()
            memories.findByIds(pending).forEach(::index)
        }.onFailure { error ->
            log.warn("Failed to dispatch pending memory indexes", error)
        }
    }

    private fun index(context: CanonicalMemoryContext): Boolean {
        val memory = context.memory
        return try {
            indexer.index(context)
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
}

private val log = LoggerFactory.getLogger(MemoryIndexingCoordinator::class.java)
