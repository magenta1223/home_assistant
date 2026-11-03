package com.homeassistant.application.port.outbound.group

interface ComponentEmbeddingStore {
    fun find(key: ComponentEmbeddingKey): ComponentEmbedding?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: ComponentEmbedding): ComponentEmbedding
}