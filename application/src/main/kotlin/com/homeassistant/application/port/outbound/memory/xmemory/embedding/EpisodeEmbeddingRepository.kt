package com.homeassistant.application.port.outbound.memory.xmemory.embedding

import com.homeassistant.application.port.outbound.embedding.Embedding

interface EpisodeEmbeddingRepository {
    fun find(key: EpisodeEmbeddingKey): Embedding<EpisodeEmbeddingKey>?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: Embedding<EpisodeEmbeddingKey>): Embedding<EpisodeEmbeddingKey>
}
