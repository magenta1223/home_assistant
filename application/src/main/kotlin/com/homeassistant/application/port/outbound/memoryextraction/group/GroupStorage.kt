package com.homeassistant.application.port.outbound.memoryextraction.group

import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.group.Group
import com.homeassistant.domain.group.ComponentPartition
import com.homeassistant.domain.embedding.EmbeddingVector

data class StoredGroup(val group: Group, val revision: Long, val centroid: EmbeddingVector) {
    init { require(revision > 0) }
}

data class GroupSnapshot(
    val groups: List<StoredGroup>,
    val embeddings: Map<Int, Embedding<ComponentEmbeddingKey>>,
)

sealed interface GroupTarget {
    data class Existing(val id: Int) : GroupTarget { init { require(id > 0) } }
    data class Created(val key: String) : GroupTarget { init { require(key.isNotBlank()) } }
}

data class ResultingGroup(val target: GroupTarget, val componentIds: Set<Int>, val centroid: EmbeddingVector)

/** Supports create, attach, split and merge without exposing individual membership edits. */
data class GroupChange(
    val operationKey: String,
    val expectedGroups: Map<Int, Long>,
    val incomingComponentIds: Set<Int>,
    val resultingGroups: List<ResultingGroup>,
    val embeddings: Map<Int, Embedding<ComponentEmbeddingKey>>,
) {
    init {
        require(operationKey.isNotBlank())
        require(expectedGroups.all { (id, revision) -> id > 0 && revision > 0 })
        require(incomingComponentIds.all { it > 0 })
        ComponentPartition(resultingGroups.map { it.componentIds })
        require(resultingGroups.map { it.target }.distinct().size == resultingGroups.size)
        require(resultingGroups.mapNotNull { (it.target as? GroupTarget.Existing)?.id }.all { it in expectedGroups })
        require(embeddings.keys == resultingGroups.flatMap { it.componentIds }.toSet())
        require(embeddings.all { (id, embedding) -> id == embedding.key.componentId })
    }
}

/** A [GroupChange] lost to a concurrent change: an expected Group revision no longer matches. */
class GroupChangeConflict(message: String) : IllegalStateException(message)

/** Stored Groups. Trusted internal metadata only; this is not an ACL-filtered user projection. */
interface GroupRepository {
    /**
     * Every Group with its revision, centroid and the Component vectors it used, read at one point in time.
     * It has no condition: placement takes the nearest centroid of every Group, and split and merge score the whole
     * partition, so every Group is an input to the decision.
     */
    fun findSnapshot(): GroupSnapshot

    /**
     * Every Group holding a Component [userId] can read, with only those Components and its stored centroid, for search.
     * A Group with no such Component is left out, since nothing in it could be returned.
     */
    fun findVisibleTo(userId: UserId): List<StoredGroup>

    /**
     * Atomically checks revisions, validates the exact partition and replaces membership and centroids; returns the
     * resulting Groups in [GroupChange.resultingGroups] order. A change whose operation key was saved before returns
     * the Groups as that change saved them.
     * @throws GroupChangeConflict when an expected Group revision changed.
     */
    fun save(change: GroupChange): List<Group>
}
