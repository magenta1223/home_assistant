package com.homeassistant.application.port.outbound.embedding

interface EpisodeEmbeddingRepository {
    fun find(key: EpisodeEmbeddingKey): Embedding<EpisodeEmbeddingKey>?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: Embedding<EpisodeEmbeddingKey>): Embedding<EpisodeEmbeddingKey>
}
