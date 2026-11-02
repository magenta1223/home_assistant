package com.homeassistant.application.port.output.component

import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.group.descriptionFingerprint
import com.homeassistant.domain.group.ComponentVector

/** An episode that belongs to no Component yet. Content is immutable, so its hash keys the vector cache. */
data class PendingEpisode(val id: Int, val content: String) {
    init { require(id > 0 && content.isNotBlank()) }
    fun embeddingKey(space: EmbeddingSpace) = EpisodeEmbeddingKey.of(id, content, space)
}

data class EpisodeEmbeddingKey(val episodeId: Int, val contentHash: String, val space: EmbeddingSpace) {
    init { require(episodeId > 0 && contentHash.isNotBlank()) }

    companion object {
        /** The key an episode's vector is cached under in [space]. */
        fun of(episodeId: Int, content: String, space: EmbeddingSpace) =
            EpisodeEmbeddingKey(episodeId, descriptionFingerprint(content), space)
    }
}

data class EpisodeEmbedding(val key: EpisodeEmbeddingKey, val vector: ComponentVector)

interface EpisodeEmbeddingGenerator {
    val space: EmbeddingSpace
    /** True when [generate] can reach the embedding service now. */
    fun isAvailable(): Boolean
    fun generate(content: String): ComponentVector
}

interface EpisodeEmbeddingStore {
    fun find(key: EpisodeEmbeddingKey): EpisodeEmbedding?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: EpisodeEmbedding): EpisodeEmbedding
}
