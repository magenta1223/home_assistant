package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.topic.SearchTopics
import com.homeassistant.application.port.input.topic.TopicMatch
import com.homeassistant.application.port.input.topic.TopicMatchMemory
import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.group.GroupReader
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.topic.MemoryEmbeddingKey
import com.homeassistant.application.port.output.topic.MemoryEmbeddingStore
import com.homeassistant.application.port.output.topic.QueryEmbeddingGenerator
import com.homeassistant.application.port.output.topic.TopicReader
import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.TopicVector
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.retrieval.BackboneSelection

/** How wide each level of [TopicSearcher] looks and how many it keeps. */
data class TopicSearchSettings(
    val groupPool: Int = 5,
    val groupLimit: Int = 3,
    val topicPool: Int = 20,
    val topicLimit: Int = 5,
) {
    init { require(groupPool > 0 && groupLimit > 0 && topicPool > 0 && topicLimit > 0) }
}

/**
 * xMemory-style search. Candidates are the Topics of the backbone Groups among the nearest Groups, plus the nearest
 * Topics directly, so a Topic not yet in a Group is still found. Of the candidates the requester can read, the backbone
 * Topics are kept, and each memory goes to the first kept Topic holding it, most relevant to the question first.
 */
class TopicSearcher(
    private val topics: TopicReader,
    private val groups: GroupReader,
    private val queries: QueryEmbeddingGenerator,
    private val memories: MemoryReader,
    private val memoryVectors: MemoryEmbeddingStore,
    private val space: EmbeddingSpace,
    private val accessPolicy: UserAccessPolicy,
    private val settings: TopicSearchSettings = TopicSearchSettings(),
    private val backbone: BackboneSelection = BackboneSelection(),
) : SearchTopics {
    override fun search(userId: UserId, query: String): List<TopicMatch> {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
        val centroids = topics.centroids().mapValues { it.value.direction }
        if (centroids.isEmpty()) return emptyList()
        val question = queries.generate(query.trim())
        val readable = readableMemories(userId, candidateTopics(question, centroids))
        val kept = backbone.select(centroids.filterKeys { it in readable }, question, settings.topicLimit)
        return matches(kept, readable, centroids, question)
    }

    /** Topics of the backbone Groups among the nearest [TopicSearchSettings.groupPool], and the nearest Topics. */
    private fun candidateTopics(question: TopicVector, centroids: Map<Int, TopicVector>): Set<Int> {
        val byId = groups.groups().associateBy { it.group.id }
        val nearGroups = nearest(byId.mapValues { it.value.centroid }, question, settings.groupPool)
        val backboneGroups = backbone.select(nearGroups, question, settings.groupLimit)
        return backboneGroups.flatMap { byId.getValue(it).group.topicIds }.toSet() +
            nearest(centroids, question, settings.topicPool).keys
    }

    /** Each candidate Topic's memories the requester can read; Topics with none are left out. */
    private fun readableMemories(userId: UserId, topicIds: Set<Int>): Map<Int, List<Memory>> {
        // The reader re-checks access, so a slice is never trusted on its own.
        val visible = memories.getMemories(userId).associateBy { it.id }
        return topics.read(topicIds).associate { topic -> topic.id to topic.memoryIdsReadableBy(userId).mapNotNull { visible[it] } }
            .filterValues { it.isNotEmpty() }
    }

    private fun matches(
        kept: List<Int>,
        readable: Map<Int, List<Memory>>,
        centroids: Map<Int, TopicVector>,
        question: TopicVector,
    ): List<TopicMatch> {
        val placed = mutableSetOf<Int>()
        return kept.mapNotNull { topicId ->
            val unplaced = readable.getValue(topicId).filter { placed.add(it.id) }
            if (unplaced.isEmpty()) null
            else TopicMatch(topicId, GroupGeometry.cosine(question, centroids.getValue(topicId)), byRelevance(unplaced, question))
        }
    }

    private fun byRelevance(memories: List<Memory>, question: TopicVector): List<TopicMatchMemory> =
        memories.sortedBy { it.id }.sortedByDescending { GroupGeometry.cosine(question, vectorOf(it)) }
            .map { TopicMatchMemory(it.id, it.content, it.createdAt) }

    private fun vectorOf(memory: Memory): TopicVector =
        checkNotNull(memoryVectors.find(MemoryEmbeddingKey.of(memory.id, memory.content, space))) {
            "memory ${memory.id} has a Topic but no vector in $space"
        }.vector

    private fun nearest(vectors: Map<Int, TopicVector>, question: TopicVector, limit: Int): Map<Int, TopicVector> =
        vectors.entries.sortedWith(compareByDescending<Map.Entry<Int, TopicVector>> { GroupGeometry.cosine(question, it.value) }
            .thenBy { it.key }).take(limit).associate { it.key to it.value }
}
