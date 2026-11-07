package com.homeassistant.application.port.outbound.embedding

data class EpisodeEmbeddingKey(val episodeId: Int, val contentHash: String, val space: EmbeddingSpace) {
    init { require(episodeId > 0 && contentHash.isNotBlank()) }

    companion object {
        /** The key an episode's vector is cached under in [space]. */
        fun of(episodeId: Int, content: String, space: EmbeddingSpace) =
            EpisodeEmbeddingKey(episodeId, contentFingerprint(content), space)
    }
}
