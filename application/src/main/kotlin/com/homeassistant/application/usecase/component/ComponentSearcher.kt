package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.input.component.SearchComponents
import com.homeassistant.application.port.input.component.ComponentMatch
import com.homeassistant.application.port.input.component.ComponentMatchEpisode
import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.group.GroupReader
import com.homeassistant.application.port.output.episode.read.EpisodeReader
import com.homeassistant.application.port.output.component.EpisodeEmbeddingKey
import com.homeassistant.application.port.output.component.EpisodeEmbeddingStore
import com.homeassistant.application.port.output.component.QueryEmbeddingGenerator
import com.homeassistant.application.port.output.component.ComponentReader
import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.retrieval.BackboneSelection

/**
 * How wide each level of [ComponentSearcher] looks and how many it keeps. [groupCandidates] caps the nearest
 * [groupPool] Groups plus their neighbors, as xMemory's Stage I pool of 20 does.
 */
data class ComponentSearchSettings(
    val groupPool: Int = 5,
    val groupCandidates: Int = 20,
    val groupLimit: Int = 3,
    val componentPool: Int = 20,
    val componentLimit: Int = 5,
) {
    init {
        require(groupPool > 0 && groupLimit > 0 && componentPool > 0 && componentLimit > 0)
        require(groupCandidates >= groupPool)
    }
}

/**
 * xMemory-style search. Candidate Groups are the nearest Groups and their kNN neighbor Groups, which reach context
 * next to the question that the question itself is not near. Candidate Components are the Components of the backbone Groups,
 * plus the nearest Components directly, so a Component not yet in a Group is still found. Of the candidates the requester can
 * read, the backbone Components are kept, and each episode goes to the first kept Component holding it, most relevant to the
 * question first.
 */
class ComponentSearcher(
    private val components: ComponentReader,
    private val groups: GroupReader,
    private val queries: QueryEmbeddingGenerator,
    private val episodes: EpisodeReader,
    private val episodeVectors: EpisodeEmbeddingStore,
    private val space: EmbeddingSpace,
    private val accessPolicy: UserAccessPolicy,
    private val settings: ComponentSearchSettings = ComponentSearchSettings(),
    private val backbone: BackboneSelection = BackboneSelection(),
) : SearchComponents {
    override fun search(userId: UserId, query: String): List<ComponentMatch> {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
        val centroids = components.centroids().mapValues { it.value.direction }
        if (centroids.isEmpty()) return emptyList()
        val question = queries.generate(query.trim())
        val readable = readableEpisodes(userId, candidateComponents(question, centroids))
        val kept = backbone.select(readable.keys, centroids, question, settings.componentLimit)
        return matches(kept, readable, centroids, question)
    }

    /** Components of the backbone candidate Groups, and the nearest [ComponentSearchSettings.componentPool] Components. */
    private fun candidateComponents(question: ComponentVector, centroids: Map<Int, ComponentVector>): Set<Int> {
        val byId = groups.groups().associateBy { it.group.id }
        val groupCentroids = byId.mapValues { it.value.centroid }
        val candidates = candidateGroups(question, groupCentroids)
        val backboneGroups = backbone.select(candidates, groupCentroids, question, settings.groupLimit)
        return backboneGroups.flatMap { byId.getValue(it).group.componentIds }.toSet() +
            nearest(question, centroids, settings.componentPool)
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

    /** Each candidate Component's episodes the requester can read; Components with none are left out. */
    private fun readableEpisodes(userId: UserId, componentIds: Set<Int>): Map<Int, List<Episode>> {
        // The reader re-checks access, so a slice is never trusted on its own.
        val visible = episodes.getEpisodes(userId).associateBy { it.id }
        return components.read(componentIds).associate { component -> component.id to component.episodeIdsReadableBy(userId).mapNotNull { visible[it] } }
            .filterValues { it.isNotEmpty() }
    }

    private fun matches(
        kept: List<Int>,
        readable: Map<Int, List<Episode>>,
        centroids: Map<Int, ComponentVector>,
        question: ComponentVector,
    ): List<ComponentMatch> {
        val placed = mutableSetOf<Int>()
        return kept.mapNotNull { componentId ->
            val unplaced = readable.getValue(componentId).filter { placed.add(it.id) }
            if (unplaced.isEmpty()) null
            else ComponentMatch(componentId, GroupGeometry.cosine(question, centroids.getValue(componentId)), byRelevance(unplaced, question))
        }
    }

    private fun byRelevance(episodes: List<Episode>, question: ComponentVector): List<ComponentMatchEpisode> =
        episodes.sortedBy { it.id }.sortedByDescending { GroupGeometry.cosine(question, vectorOf(it)) }
            .map { ComponentMatchEpisode(it.id, it.content, it.createdAt) }

    private fun vectorOf(episode: Episode): ComponentVector =
        checkNotNull(episodeVectors.find(EpisodeEmbeddingKey.of(episode.id, episode.content, space))) {
            "episode ${episode.id} has a Component but no vector in $space"
        }.vector

    private fun nearest(question: ComponentVector, vectors: Map<Int, ComponentVector>, limit: Int): List<Int> =
        GroupGeometry.nearest(question, vectors, limit).map { it.first }
}
