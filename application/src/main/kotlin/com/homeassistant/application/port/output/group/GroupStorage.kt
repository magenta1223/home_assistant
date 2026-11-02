package com.homeassistant.application.port.output.group

import com.homeassistant.domain.group.Group
import com.homeassistant.domain.group.ComponentPartition
import com.homeassistant.domain.group.ComponentVector

data class StoredGroup(val group: Group, val revision: Long, val centroid: ComponentVector) {
    init { require(revision > 0) }
}

/** [componentsWithOutdatedGroupVector] are grouped components whose centroid moved after their Group stored its vector. */
data class GroupSnapshot(
    val groups: List<StoredGroup>,
    val embeddings: Map<Int, ComponentEmbedding>,
    val componentsWithOutdatedGroupVector: List<PendingComponent> = emptyList(),
)

/** Trusted internal metadata only; this is not an ACL-filtered user projection. */
fun interface GroupReader {
    /** Every Group with its member Components and centroid. */
    fun groups(): List<StoredGroup>
}

/** Trusted internal metadata only; this is not an ACL-filtered user projection. */
interface GroupSnapshotReader {
    fun snapshot(): GroupSnapshot
}

sealed interface GroupTarget {
    data class Existing(val id: Int) : GroupTarget { init { require(id > 0) } }
    data class Created(val key: String) : GroupTarget { init { require(key.isNotBlank()) } }
}

data class ResultingGroup(val target: GroupTarget, val componentIds: Set<Int>, val centroid: ComponentVector)

/** Supports create, attach, split and merge without exposing individual membership edits. */
data class GroupChange(
    val operationKey: String,
    val expectedGroups: Map<Int, Long>,
    val incomingComponentIds: Set<Int>,
    val resultingGroups: List<ResultingGroup>,
    val embeddings: Map<Int, ComponentEmbedding>,
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

sealed interface GroupWriteResult {
    data class Applied(val groupIds: List<Int>) : GroupWriteResult
    data class AlreadyApplied(val groupIds: List<Int>) : GroupWriteResult
    data object Conflict : GroupWriteResult
}

interface GroupChangeWriter {
    /** Atomic revision check, exact partition validation, membership/centroid update and receipt. */
    fun apply(change: GroupChange): GroupWriteResult
}
