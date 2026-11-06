package com.homeassistant.application.usecase.contextprovider.component

import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRepository
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentRepository
import com.homeassistant.domain.embedding.EmbeddingGeometry
import com.homeassistant.domain.embedding.EmbeddingVector
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
import com.homeassistant.application.usecase.memoryextraction.group.pending
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.retrieval.BackboneSelection

/**
 * xMemory-style search. Candidate Groups are the nearest Groups and their kNN neighbor Groups, which reach context
 * next to the question that the question itself is not near. Candidate Components are the Components of the backbone Groups,
 * plus the nearest Components directly, so a Component not yet in a Group is still found. Of the candidates the requester can
 * read, the backbone Components are kept, each with its source episode; an episode goes to the first kept Component it is the
 * source of.
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
    fun search(userId: UserId, query: String): List<ComponentMatch> {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
        val readable = components.findVisibleTo(userId)
        val vectors = readable.associate { it.id to componentEmbeddings.prepare(it.pending()).vector }
        if (vectors.isEmpty()) return emptyList()
        val question = queries.embedQuery(query.trim())
        val sources = sourcesOf(userId, readable)
        val kept = backbone.select(candidateComponents(userId, question, vectors) intersect sources.keys, vectors, question,
            settings.componentLimit)
        return matches(kept, sources, vectors, question)
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

    /** Each of [readable] with its source episode, when the requester can read that episode too. */
    private fun sourcesOf(userId: UserId, readable: List<Component>): Map<Int, Episode> {
        val visible = episodes.findVisibleTo(userId).associateBy { it.id }
        return readable
            .mapNotNull { component -> visible[component.sourceEpisodeId]?.let { component.id to it } }
            .toMap()
    }

    private fun matches(
        kept: List<Int>,
        sources: Map<Int, Episode>,
        vectors: Map<Int, EmbeddingVector>,
        question: EmbeddingVector,
    ): List<ComponentMatch> {
        val placed = mutableSetOf<Int>()
        return kept.mapNotNull { componentId ->
            val source = sources.getValue(componentId)
            if (!placed.add(source.id)) null
            else ComponentMatch(componentId, EmbeddingGeometry.cosine(question, vectors.getValue(componentId)),
                listOf(ComponentMatchEpisode(source.id, source.content, source.createdAt)))
        }
    }

    private fun nearest(question: EmbeddingVector, vectors: Map<Int, EmbeddingVector>, limit: Int): List<Int> =
        EmbeddingGeometry.nearest(question, vectors, limit).map { it.first }
}
