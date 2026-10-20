package com.homeassistant.application.port.output.group

import com.homeassistant.domain.group.Group
import com.homeassistant.domain.group.TopicPartition
import com.homeassistant.domain.group.TopicVector

data class StoredGroup(val group: Group, val revision: Long, val centroid: TopicVector) {
    init { require(revision > 0) }
}

/** [staleTopics] are grouped topics whose current vector version no longer matches their membership vector. */
data class GroupSnapshot(
    val groups: List<StoredGroup>,
    val embeddings: Map<Int, TopicEmbedding>,
    val staleTopics: List<PendingTopic> = emptyList(),
)

/** Trusted internal metadata only; this is not an ACL-filtered user projection. */
interface GroupSnapshotReader {
    fun snapshot(): GroupSnapshot
}

sealed interface GroupTarget {
    data class Existing(val id: Int) : GroupTarget { init { require(id > 0) } }
    data class Created(val key: String) : GroupTarget { init { require(key.isNotBlank()) } }
}

data class ResultingGroup(val target: GroupTarget, val topicIds: Set<Int>, val centroid: TopicVector)

/** Supports create, attach, split and future merge without exposing individual membership edits. */
data class GroupChange(
    val operationKey: String,
    val expectedGroups: Map<Int, Long>,
    val incomingTopicIds: Set<Int>,
    val resultingGroups: List<ResultingGroup>,
    val embeddings: Map<Int, TopicEmbedding>,
) {
    init {
        require(operationKey.isNotBlank())
        require(expectedGroups.all { (id, revision) -> id > 0 && revision > 0 })
        require(incomingTopicIds.all { it > 0 })
        TopicPartition(resultingGroups.map { it.topicIds })
        require(resultingGroups.map { it.target }.distinct().size == resultingGroups.size)
        require(resultingGroups.mapNotNull { (it.target as? GroupTarget.Existing)?.id }.all { it in expectedGroups })
        require(embeddings.keys == resultingGroups.flatMap { it.topicIds }.toSet())
        require(embeddings.all { (id, embedding) -> id == embedding.key.topicId })
    }
}

sealed interface GroupWriteResult {
    data class Applied(val groupIds: List<Int>) : GroupWriteResult
    data class AlreadyApplied(val groupIds: List<Int>) : GroupWriteResult
    data object Conflict : GroupWriteResult
}

interface GroupChangeWriter {
    /** Atomic revision check, exact partition validation, membership/centroid update and receipt. */
    fun apply(change: GroupChange): GroupWriteResult
}
