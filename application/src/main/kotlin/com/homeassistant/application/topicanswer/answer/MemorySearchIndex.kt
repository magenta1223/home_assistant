package com.homeassistant.application.topicanswer.answer

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory

data class MemorySearchDocument(
    val memory: Memory,
    val topicTitle: String,
    val topicSummary: String,
    val sourceType: String,
    val sourceName: String,
)

data class MemorySearchHit(
    val topicId: Int,
    val memoryId: Int,
    val score: Double,
)

interface MemorySearchIndex {
    fun index(document: MemorySearchDocument)
    fun search(userId: UserId, question: String, limit: Int): List<MemorySearchHit>
}

private object UnavailableMemorySearchIndex : MemorySearchIndex {
    override fun index(document: MemorySearchDocument) {
        // Indexing is skipped until a real embedding provider is wired.
    }

    override fun search(
        userId: UserId,
        question: String,
        limit: Int,
    ): List<MemorySearchHit> =
        throw MemorySearchIndexUnavailableException("memory vector index is not configured")
}

object MemorySearchIndexes {
    fun unavailable(): MemorySearchIndex =
        UnavailableMemorySearchIndex
}

class MemorySearchIndexUnavailableException(message: String) : RuntimeException(message)
