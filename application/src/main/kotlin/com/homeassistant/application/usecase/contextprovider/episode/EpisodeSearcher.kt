package com.homeassistant.application.usecase.contextprovider.episode

import com.homeassistant.application.port.outbound.component.QueryEmbeddingGenerator
import com.homeassistant.application.port.outbound.episode.EpisodeReader
import com.homeassistant.application.usecase.component.PrepareEpisodeEmbedding
import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId

/**
 * Ranks the episodes the requester can read by cosine to the question. Components hold only long-term knowledge, so
 * this is how a one-time event that left no Component is still found.
 * TODO: Interface for searcher like interface Searcher<T>
 */
class EpisodeSearcher(
    private val episodes: EpisodeReader,
    private val queries: QueryEmbeddingGenerator,
    private val embeddings: PrepareEpisodeEmbedding,
    private val accessPolicy: UserAccessPolicy,
    private val limit: Int = DEFAULT_LIMIT,
) {
    init { require(limit > 0) { "limit must be positive" } }

    fun search(userId: UserId, query: String): List<EpisodeMatch> {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
        val readable = episodes.getEpisodes(userId).associateBy { it.id }
        if (readable.isEmpty()) return emptyList()
        val question = queries.generate(query.trim())
        val vectors = readable.mapValues { (id, episode) -> embeddings.prepare(id, episode.content).vector }
        return GroupGeometry.nearest(question, vectors, limit).map { (id, score) ->
            val episode = readable.getValue(id)
            EpisodeMatch(id, score, episode.content, episode.createdAt)
        }
    }

    private companion object {
        /** xMemory retrieves 10 episodes beside its components. */
        const val DEFAULT_LIMIT = 10
    }
}