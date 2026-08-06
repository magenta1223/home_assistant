package com.homeassistant.application.memory.answer

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.source.SourceDescriptor

data class MemorySearchDocument(
    val memory: Memory,
    val topic: MemorySearchTopic? = null,
)

data class MemorySearchTopic(
    val title: String,
    val summary: String,
    val source: SourceDescriptor,
)

data class MemorySearchHit(
    val topicId: Int?,
    val memoryId: Int,
    val score: Double,
)

interface MemorySearchIndex {
    fun index(document: MemorySearchDocument)
    fun search(userId: UserId, question: String, limit: Int): List<MemorySearchHit>
}

class MemorySearchIndexUnavailableException(message: String) : RuntimeException(message)
