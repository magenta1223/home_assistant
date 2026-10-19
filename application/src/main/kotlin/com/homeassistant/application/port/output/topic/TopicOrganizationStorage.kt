package com.homeassistant.application.port.output.topic

import com.homeassistant.domain.group.TopicPartition
import com.homeassistant.domain.group.TopicVector

/** Topic category under which organized memberships are stored; xMemory itself has no categories. */
const val ORGANIZED_TOPIC_CATEGORY = "기본"

/** An automatically organized Topic: its organized memories, membership revision and centroid. */
data class OrganizedTopic(val topicId: Int, val memoryIds: Set<Int>, val revision: Long, val centroid: TopicVector) {
    init { require(topicId > 0 && memoryIds.isNotEmpty() && revision > 0) }
}

data class TopicOrganizationSnapshot(val topics: List<OrganizedTopic>, val embeddings: Map<Int, MemoryEmbedding>)

/** Trusted internal metadata only; this is not an ACL-filtered user projection. */
interface TopicOrganizationSnapshotReader {
    fun snapshot(): TopicOrganizationSnapshot
}

sealed interface TopicTarget {
    data class Existing(val id: Int) : TopicTarget { init { require(id > 0) } }

    /** A new Topic is described by its smallest memory's content until its summary is generated. */
    data object Created : TopicTarget
}

data class ResultingTopic(val target: TopicTarget, val memoryIds: Set<Int>, val centroid: TopicVector)

/** Redistributes the organized memories of [expectedTopics] plus [incomingMemoryIds] into [resultingTopics]. */
data class TopicOrganizationChange(
    val expectedTopics: Map<Int, Long>,
    val incomingMemoryIds: Set<Int>,
    val resultingTopics: List<ResultingTopic>,
    val embeddings: Map<Int, MemoryEmbedding>,
) {
    init {
        require(expectedTopics.all { (id, revision) -> id > 0 && revision > 0 })
        require(incomingMemoryIds.all { it > 0 })
        TopicPartition(resultingTopics.map { it.memoryIds })
        val existing = resultingTopics.mapNotNull { (it.target as? TopicTarget.Existing)?.id }
        // Topics are never removed: merging is not supported, so every expected Topic keeps a part.
        require(existing.distinct().size == existing.size && existing.toSet() == expectedTopics.keys)
        require(embeddings.keys == resultingTopics.flatMap { it.memoryIds }.toSet())
        require(embeddings.all { (id, embedding) -> id == embedding.key.memoryId })
    }
}

sealed interface TopicOrganizationWriteResult {
    data class Applied(val topicIds: List<Int>) : TopicOrganizationWriteResult
    data object Conflict : TopicOrganizationWriteResult
}

/** Organized memberships whose description has not been generated for the current revision. */
data class TopicDescriptionRequest(val topicId: Int, val revision: Long, val memoryContents: List<String>) {
    init { require(topicId > 0 && revision > 0 && memoryContents.isNotEmpty()) }
}

interface PendingTopicDescriptionSource {
    fun pendingDescriptions(limit: Int): List<TopicDescriptionRequest>
}

sealed interface TopicDescriptionWriteResult {
    data object Applied : TopicDescriptionWriteResult
    data object Conflict : TopicDescriptionWriteResult
}

interface TopicOrganizationWriter {
    /** Atomic revision check, exact partition validation, membership/centroid update and Topic creation. */
    fun apply(change: TopicOrganizationChange): TopicOrganizationWriteResult

    /** Stores [description] only while the Topic is still at [revision]. */
    fun describe(topicId: Int, revision: Long, description: String): TopicDescriptionWriteResult
}

/** Summarizes the shared subject of organized memories into a short Topic description. */
fun interface TopicDescriptionGenerator {
    fun describe(memoryContents: List<String>): String
}

class TopicRebuildRequired(message: String) : IllegalStateException(message)
