package com.homeassistant.application.port.outbound.memoryextraction.group

import com.homeassistant.application.port.outbound.embedding.Embedding

interface ComponentEmbeddingStore {
    fun find(key: ComponentEmbeddingKey): Embedding<ComponentEmbeddingKey>?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: Embedding<ComponentEmbeddingKey>): Embedding<ComponentEmbeddingKey>
}
