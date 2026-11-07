package com.homeassistant.application.usecase.memoryextraction.component

import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentExtractor
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentRepository
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeComponents
import com.homeassistant.application.port.outbound.memoryextraction.component.PendingEpisode
import com.homeassistant.application.usecase.embedding.PrepareComponentEmbedding
import com.homeassistant.application.usecase.embedding.PrepareEpisodeEmbedding
import com.homeassistant.domain.embedding.EmbeddingGeometry
import com.homeassistant.domain.embedding.EmbeddingVector

/**
 * Extracts an episode's Components as xMemory does: the Components nearest the episode stand for what is already known,
 * and only knowledge they do not state becomes new Components.
 */
class AssignEpisodeToComponents(
    private val components: ComponentRepository,
    private val episodeEmbeddings: PrepareEpisodeEmbedding,
    private val componentEmbeddings: PrepareComponentEmbedding,
    private val extractor: ComponentExtractor,
    private val knownLimit: Int = DEFAULT_KNOWN_LIMIT,
) {
    init { require(knownLimit > 0) { "knownLimit must be positive" } }

    /** True when the episode embedding can be called now. */
    fun isReady(): Boolean = episodeEmbeddings.isAvailable()

    /** The ids of the Components created for [episode]; empty when it held no new long-term knowledge. */
    fun assign(episode: PendingEpisode): List<Int> {
        // Every episode is embedded here, so episode search later finds it whether or not it yields Components.
        val vector = episodeEmbeddings.prepare(episode.id, episode.content).vector
        val statements = extractor.extract(episode, knownNear(episode, vector))
            .map(String::trim).filter(String::isNotEmpty).distinct()
        val saved = components.save(EpisodeComponents(episode.id, statements))
        saved.forEach { componentEmbeddings.prepare(it) }
        return saved.map { it.id }
    }

    /**
     * The statements of the Components nearest [episode]. Only Components every reader of the episode may read are used,
     * so knowledge hidden from them never shapes what is extracted for them.
     */
    private fun knownNear(episode: PendingEpisode, vector: EmbeddingVector): List<String> {
        val known = components.findCovering(episode.access)
        if (known.isEmpty()) return emptyList()
        val vectors = known.associate { it.id to componentEmbeddings.prepare(it).vector }
        val statements = known.associate { it.id to it.statement }
        return EmbeddingGeometry.nearest(vector, vectors, knownLimit).map { statements.getValue(it.first) }
    }

    private companion object {
        const val DEFAULT_KNOWN_LIMIT = 10
    }
}
