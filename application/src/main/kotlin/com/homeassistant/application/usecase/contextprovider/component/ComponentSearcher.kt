package com.homeassistant.application.usecase.contextprovider.component

import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRepository
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentRepository
import com.homeassistant.domain.embedding.EmbeddingGeometry
import com.homeassistant.domain.embedding.EmbeddingVector
import com.homeassistant.application.port.inbound.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.application.usecase.embedding.PrepareComponentEmbedding
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.retrieval.BackboneSelection

/**
 * xMemory-style search. Candidate Groups are the nearest Groups and their kNN neighbor Groups, which reach context
 * next to the question that the question itself is not near. Candidate Components are the Components of the backbone Groups,
 * plus the nearest Components directly, so a Component not yet in a Group is still found. Of the candidates whose source
 * episode the requester can read, the backbone Components are kept.
 */
class ComponentSearcher(
    private val components: ComponentRepository,
    private val groups: GroupRepository,
    private val queries: EmbeddingGenerator,
    private val episodes: EpisodeRepository,
    private val componentEmbeddings: PrepareComponentEmbedding,
    private val accessPolicy: UserAccessPolicy,
    private val settings: ComponentSearchSettings = ComponentSearchSettings(),
    private val backbone: BackboneSelection = BackboneSelection(),
) {
    fun search(userId: UserId, query: String): List<Component> {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
        val readable = components.findVisibleTo(userId)
        val vectors = readable.associate { it.id to componentEmbeddings.prepare(it).vector }
        if (vectors.isEmpty()) return emptyList()
        val question = queries.embedQuery(query.trim())
        val withReadableSource = withReadableSource(userId, readable)
        val kept = backbone.select(candidateComponents(userId, question, vectors) intersect withReadableSource, vectors, question,
            settings.componentLimit)
        val byId = readable.associateBy { it.id }
        return kept.map { byId.getValue(it) }
    }

    /**
     * The readable Components of the backbone candidate Groups, and the nearest [ComponentSearchSettings.componentPool]
     * readable Components.
     */
    private fun candidateComponents(userId: UserId, question: EmbeddingVector, vectors: Map<Int, EmbeddingVector>): Set<Int> {
        val byId = groups.findVisibleTo(userId).associateBy { it.group.id }
        val groupCentroids = byId.mapValues { it.value.centroid }
        val candidates = candidateGroups(question, groupCentroids)
        val backboneGroups = backbone.select(candidates, groupCentroids, question, settings.groupLimit)
        return backboneGroups
            .flatMap { byId.getValue(it).group.componentIds }.toSet() +
                nearest(question, vectors, settings.componentPool)
    }

    /**
     * The nearest [ComponentSearchSettings.groupPool] Groups, then their neighbors most similar to the Group they neighbor,
     * up to [ComponentSearchSettings.groupCandidates]. Neighbors are not ranked by the question, which would drop first the
     * context next to the question that the expansion is for.
     */
    private fun candidateGroups(question: EmbeddingVector, groupCentroids: Map<Int, EmbeddingVector>): Set<Int> {
        val nearGroups = nearest(question, groupCentroids, settings.groupPool)
        val linked = nearGroups.flatMap { backbone.neighborsOf(it, groupCentroids) }
            .filter { it.first !in nearGroups }
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
            .map { it.first }
        return (nearGroups + linked).distinct().take(settings.groupCandidates).toSet()
    }

    /** The ids of [readable] whose source episode the requester can read too. */
    private fun withReadableSource(userId: UserId, readable: List<Component>): Set<Int> {
        val visible = episodes.findVisibleTo(userId).map { it.id }.toSet()
        return readable.filter { it.sourceEpisodeId in visible }.map { it.id }.toSet()
    }

    private fun nearest(question: EmbeddingVector, vectors: Map<Int, EmbeddingVector>, limit: Int): List<Int> =
        EmbeddingGeometry.nearest(question, vectors, limit).map { it.first }
}
