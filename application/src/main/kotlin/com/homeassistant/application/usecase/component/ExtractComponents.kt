package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.input.component.EpisodeAssignmentOutcome
import com.homeassistant.application.port.output.component.ComponentKnowledgeExtractor
import com.homeassistant.application.port.output.component.ComponentMaintenanceStore
import com.homeassistant.application.port.output.component.PendingEpisode
import com.homeassistant.application.usecase.group.PrepareComponentEmbedding
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.group.GroupGeometry

/**
 * Extracts an episode's Components as xMemory does: the Components nearest the episode stand for what is already known,
 * and only knowledge they do not state becomes new Components.
 */
class ExtractComponents(
    private val store: ComponentMaintenanceStore,
    private val episodeEmbeddings: PrepareEpisodeEmbedding,
    private val componentEmbeddings: PrepareComponentEmbedding,
    private val extractor: ComponentKnowledgeExtractor,
    private val knownLimit: Int = DEFAULT_KNOWN_LIMIT,
) {
    init { require(knownLimit > 0) { "knownLimit must be positive" } }

    /** True when the episode embedding can be called now. */
    fun isReady(): Boolean = episodeEmbeddings.isAvailable()

    /** Stores the new long-term knowledge of [episode] as Components and returns their ids; empty when there is none. */
    fun execute(episode: PendingEpisode): EpisodeAssignmentOutcome.Extracted {
        // Every episode is embedded here, so episode search later finds it whether or not it yields Components.
        val vector = episodeEmbeddings.prepare(episode.id, episode.content).vector
        val statements = extractor.extract(episode, knownNear(episode, vector))
            .map(String::trim).filter(String::isNotEmpty).distinct()
        val componentIds = store.saveExtraction(episode.id, statements)
        store.read(componentIds).forEach { componentEmbeddings.prepare(it.pending()) }
        return EpisodeAssignmentOutcome.Extracted(componentIds)
    }

    /**
     * The statements of the Components nearest [episode]. Only Components every reader of the episode may read are used,
     * so knowledge hidden from them never shapes what is extracted for them.
     */
    private fun knownNear(episode: PendingEpisode, vector: ComponentVector): List<String> {
        val known = store.all().filter { it.access.covers(episode.access) }
        if (known.isEmpty()) return emptyList()
        val vectors = known.associate { it.id to componentEmbeddings.prepare(it.pending()).vector }
        val statements = known.associate { it.id to it.statement }
        return GroupGeometry.nearest(vector, vectors, knownLimit).map { statements.getValue(it.first) }
    }

    private companion object {
        const val DEFAULT_KNOWN_LIMIT = 10
    }
}
