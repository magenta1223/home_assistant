package com.homeassistant.application.topicanalysis.save

import com.homeassistant.application.memory.answer.MemorySearchDocument
import com.homeassistant.application.memory.answer.MemorySearchIndex
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicAnalysisQueryStore
import org.slf4j.LoggerFactory

internal class MemoryIndexingCoordinator(
    private val topicStore: TopicAnalysisQueryStore,
    private val searchIndex: MemorySearchIndex,
    private val outbox: IndexingOutboxStore,
) {
    fun index(topic: Topic): Boolean =
        topic.memories.map { memory -> index(topic, memory.id) }.all { it }

    fun retryPending(currentMemoryIds: Set<Int>) {
        runCatching {
            val pending = outbox.pending(IndexTargetType.MEMORY).filterNot(currentMemoryIds::contains).toSet()
            topicStore.getTopicsForMemoryIndexing(pending).forEach { topic ->
                topic.memories.forEach { memory -> index(topic, memory.id) }
            }
        }.onFailure { error ->
            log.warn("Failed to dispatch pending memory indexes", error)
        }
    }

    private fun index(topic: Topic, memoryId: Int): Boolean {
        val memory = topic.memories.single { it.id == memoryId }
        return try {
            searchIndex.index(
                MemorySearchDocument(
                    memory = memory,
                    topicTitle = topic.title,
                    topicSummary = topic.summary,
                    sourceType = topic.sourceType,
                    sourceName = topic.sourceName,
                ),
            )
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
