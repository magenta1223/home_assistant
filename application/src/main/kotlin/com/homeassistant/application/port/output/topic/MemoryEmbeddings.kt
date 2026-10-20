package com.homeassistant.application.port.output.topic

import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.group.descriptionFingerprint
import com.homeassistant.domain.group.TopicVector

/** A memory that belongs to no Topic yet. Content is immutable, so its hash keys the vector cache. */
data class PendingMemory(val id: Int, val content: String) {
    init { require(id > 0 && content.isNotBlank()) }
    fun embeddingKey(space: EmbeddingSpace) = MemoryEmbeddingKey(id, descriptionFingerprint(content), space)
}

data class MemoryEmbeddingKey(val memoryId: Int, val contentHash: String, val space: EmbeddingSpace) {
    init { require(memoryId > 0 && contentHash.isNotBlank()) }
}

data class MemoryEmbedding(val key: MemoryEmbeddingKey, val vector: TopicVector)

interface MemoryEmbeddingGenerator {
    val space: EmbeddingSpace
    fun generate(content: String): TopicVector
}

interface MemoryEmbeddingStore {
    fun find(key: MemoryEmbeddingKey): MemoryEmbedding?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: MemoryEmbedding): MemoryEmbedding
}
