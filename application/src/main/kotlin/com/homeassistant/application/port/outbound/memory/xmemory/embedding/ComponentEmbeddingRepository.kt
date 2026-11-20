package com.homeassistant.application.port.outbound.memory.xmemory.embedding

import com.homeassistant.application.port.outbound.embedding.Embedding

interface ComponentEmbeddingRepository {
    fun find(key: ComponentEmbeddingKey): Embedding<ComponentEmbeddingKey>?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: Embedding<ComponentEmbeddingKey>): Embedding<ComponentEmbeddingKey>
}
