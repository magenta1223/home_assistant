package com.homeassistant.application.usecase.contextprovider.episode

import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.application.usecase.memoryextraction.component.PrepareEpisodeEmbedding
import com.homeassistant.domain.embedding.EmbeddingGeometry
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId

/**
 * Ranks the episodes the requester can read by cosine to the question. Components hold only long-term knowledge, so
 * this is how a one-time event that left no Component is still found.
 */
class EpisodeSearcher(
    private val episodes: EpisodeRepository,
    private val queries: EmbeddingGenerator,
    private val embeddings: PrepareEpisodeEmbedding,
    private val accessPolicy: UserAccessPolicy,
    private val limit: Int = DEFAULT_LIMIT,
) {
    init { require(limit > 0) { "limit must be positive" } }

    fun search(userId: UserId, query: String): List<Episode> {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
        val readable = episodes.findVisibleTo(userId).associateBy { it.id }
        if (readable.isEmpty()) return emptyList()
        val question = queries.embedQuery(query.trim())
        val vectors = readable.mapValues { (id, episode) -> embeddings.prepare(id, episode.content).vector }
        return EmbeddingGeometry
            .nearest(question, vectors, limit)
            .map { (id, _) -> readable.getValue(id) }
    }

    private companion object {
        /** xMemory retrieves 10 episodes beside its components. */
        const val DEFAULT_LIMIT = 10
    }
}