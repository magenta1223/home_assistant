package com.homeassistant.application.usecase.memoryextraction.component

import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingStore

/** Provides an episode's vector for Component placement, generating and storing it only when its content is not stored yet. */
class PrepareEpisodeEmbedding(
    private val generator: EmbeddingGenerator,
    private val store: EpisodeEmbeddingStore,
) {
    /** The model and input format every prepared vector belongs to. */
    val space: EmbeddingSpace get() = generator.space

    /** True when the embedding generator can be called now. */
    fun isAvailable(): Boolean = generator.isAvailable()

    /** The stored vector of an episode's [content] in [space], generated and stored first when missing. */
    fun prepare(episodeId: Int, content: String): Embedding<EpisodeEmbeddingKey> {
        val key = EpisodeEmbeddingKey.of(episodeId, content, space)
        return store.find(key) ?: store.save(Embedding(key, generator.embedDocument(content)))
    }
}
