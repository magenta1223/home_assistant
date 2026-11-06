package com.homeassistant.application.usecase.contextprovider.component

import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupReader
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeReader
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentReader
import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
import com.homeassistant.application.usecase.memoryextraction.group.pending
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
    private val components: ComponentReader,
    private val groups: GroupReader,
    private val queries: EmbeddingGenerator,
    private val episodes: EpisodeReader,
    private val componentEmbeddings: PrepareComponentEmbedding,
    private val accessPolicy: UserAccessPolicy,
    private val settings: ComponentSearchSettings = ComponentSearchSettings(),
    private val backbone: BackboneSelection = BackboneSelection(),
) {
    fun search(userId: UserId, query: String): List<ComponentMatch> {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
        val vectors = components.all().associate { it.id to componentEmbeddings.prepare(it.pending()).vector }
        if (vectors.isEmpty()) return emptyList()
        val question = queries.embedQuery(query.trim())
        val readable = readableSources(userId, candidateComponents(question, vectors))
        val kept = backbone.select(readable.keys, vectors, question, settings.componentLimit)
        return matches(kept, readable, vectors, question)
    }

    /** Components of the backbone candidate Groups, and the nearest [ComponentSearchSettings.componentPool] Components. */
    private fun candidateComponents(question: ComponentVector, vectors: Map<Int, ComponentVector>): Set<Int> {
        val byId = groups.groups().associateBy { it.group.id }
        val groupCentroids = byId.mapValues { it.value.centroid }
        val candidates = candidateGroups(question, groupCentroids)
        val backboneGroups = backbone.select(candidates, groupCentroids, question, settings.groupLimit)
        return backboneGroups.flatMap { byId.getValue(it).group.componentIds }.toSet() +
            nearest(question, vectors, settings.componentPool)
    }

    /**
     * The nearest [ComponentSearchSettings.groupPool] Groups, then their neighbors most similar to the Group they neighbor,
     * up to [ComponentSearchSettings.groupCandidates]. Neighbors are not ranked by the question, which would drop first the
     * context next to the question that the expansion is for.
     */
    private fun candidateGroups(question: ComponentVector, groupCentroids: Map<Int, ComponentVector>): Set<Int> {
        val nearGroups = nearest(question, groupCentroids, settings.groupPool)
        val linked = nearGroups.flatMap { backbone.neighborsOf(it, groupCentroids) }
            .filter { it.first !in nearGroups }
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
            .map { it.first }
        return (nearGroups + linked).distinct().take(settings.groupCandidates).toSet()
    }

    /** Each candidate Component's source episode when the requester can read both; other Components are left out. */
    private fun readableSources(userId: UserId, componentIds: Set<Int>): Map<Int, Episode> {
        // The reader re-checks access, so a Component's own access is never trusted on its own.
        val visible = episodes.getEpisodes(userId).associateBy { it.id }
        return components.read(componentIds).filter { it.isVisibleTo(userId) }
            .mapNotNull { component -> visible[component.sourceEpisodeId]?.let { component.id to it } }
            .toMap()
    }

    private fun matches(
        kept: List<Int>,
        sources: Map<Int, Episode>,
        vectors: Map<Int, ComponentVector>,
        question: ComponentVector,
    ): List<ComponentMatch> {
        val placed = mutableSetOf<Int>()
        return kept.mapNotNull { componentId ->
            val source = sources.getValue(componentId)
            if (!placed.add(source.id)) null
            else ComponentMatch(componentId, GroupGeometry.cosine(question, vectors.getValue(componentId)),
                listOf(ComponentMatchEpisode(source.id, source.content, source.createdAt)))
        }
    }

    private fun nearest(question: ComponentVector, vectors: Map<Int, ComponentVector>, limit: Int): List<Int> =
        GroupGeometry.nearest(question, vectors, limit).map { it.first }
}
