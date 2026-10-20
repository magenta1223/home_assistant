package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.topic.SearchTopics
import com.homeassistant.application.port.input.topic.TopicMatch
import com.homeassistant.application.port.input.topic.TopicMatchMemory
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.topic.QueryEmbeddingGenerator
import com.homeassistant.application.port.output.topic.TopicReader
import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId

/**
 * Ranks every Topic centroid by cosine to the question and walks down the ranking, keeping a Topic
 * only through the slices the requester can read, until [limit] Topics are found.
 */
class TopicSearcher(
    private val topics: TopicReader,
    private val queries: QueryEmbeddingGenerator,
    private val memories: MemoryReader,
    private val accessPolicy: UserAccessPolicy,
    private val limit: Int = DEFAULT_LIMIT,
) : SearchTopics {
    init { require(limit > 0) }

    override fun search(userId: UserId, query: String): List<TopicMatch> {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
        val centroids = topics.centroids()
        if (centroids.isEmpty()) return emptyList()
        val vector = queries.generate(query.trim())
        val ranked = centroids.mapValues { GroupGeometry.cosine(vector, it.value.direction) }.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })
        // The reader re-checks access, so a slice is never trusted on its own.
        val visible = memories.getMemories(userId).associateBy { it.id }
        val matches = mutableListOf<TopicMatch>()
        for ((topicId, score) in ranked) {
            val topic = topics.read(listOf(topicId)).singleOrNull() ?: continue
            val readable = topic.memoryIdsReadableBy(userId).mapNotNull { visible[it] }
            if (readable.isEmpty()) continue
            matches += TopicMatch(topicId, score, readable.sortedByDescending { it.createdAt }
                .map { TopicMatchMemory(it.id, it.content, it.createdAt) })
            if (matches.size == limit) break
        }
        return matches
    }

    companion object {
        const val DEFAULT_LIMIT = 3
    }
}
