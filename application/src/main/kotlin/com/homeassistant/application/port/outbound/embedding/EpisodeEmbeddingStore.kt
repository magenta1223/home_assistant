package com.homeassistant.application.port.outbound.embedding

interface EpisodeEmbeddingStore {
    fun find(key: EpisodeEmbeddingKey): Embedding<EpisodeEmbeddingKey>?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: Embedding<EpisodeEmbeddingKey>): Embedding<EpisodeEmbeddingKey>
}
