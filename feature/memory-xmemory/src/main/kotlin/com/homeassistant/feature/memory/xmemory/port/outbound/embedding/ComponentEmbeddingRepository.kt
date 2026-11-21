package com.homeassistant.feature.memory.xmemory.port.outbound.embedding

import com.homeassistant.application.port.outbound.embedding.Embedding

interface ComponentEmbeddingRepository {
    fun find(key: ComponentEmbeddingKey): Embedding<ComponentEmbeddingKey>?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: Embedding<ComponentEmbeddingKey>): Embedding<ComponentEmbeddingKey>
}
