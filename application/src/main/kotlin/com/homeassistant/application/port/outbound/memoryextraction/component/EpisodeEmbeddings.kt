package com.homeassistant.application.port.outbound.memoryextraction.component

import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.group.descriptionFingerprint
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.group.ComponentVector

/**
 * An episode whose Components are not extracted yet, with the messages it was written from, oldest first.
 */
data class PendingEpisode(
    val id: Int,
    val title: String,
    val content: String,
    val access: EpisodeAccess,
    val messages: List<String>,
) {
    init { require(id > 0 && title.isNotBlank() && content.isNotBlank() && messages.isNotEmpty()) }
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

interface EpisodeEmbeddingStore {
    fun find(key: EpisodeEmbeddingKey): EpisodeEmbedding?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: EpisodeEmbedding): EpisodeEmbedding
}
