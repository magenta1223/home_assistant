package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.component.*

/** Provides an episode's vector for Component placement, generating and storing it only when its content is not stored yet. */
class PrepareEpisodeEmbedding(
    private val generator: EpisodeEmbeddingGenerator,
    private val store: EpisodeEmbeddingStore,
) {
    /** The model and input format every prepared vector belongs to. */
    val space: EmbeddingSpace get() = generator.space

    /** True when the embedding generator can be called now. */
    fun isAvailable(): Boolean = generator.isAvailable()

    /** The stored vector of [episode]'s content in [space], generated and stored first when missing. */
    fun prepare(episode: PendingEpisode): EpisodeEmbedding {
        val key = episode.embeddingKey(space)
        return store.find(key) ?: store.save(EpisodeEmbedding(key, generator.generate(episode.content)))
    }
}
