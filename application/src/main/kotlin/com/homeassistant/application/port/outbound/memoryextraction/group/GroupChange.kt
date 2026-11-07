package com.homeassistant.application.port.outbound.memoryextraction.group

import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.domain.group.ComponentPartition
import com.homeassistant.domain.embedding.EmbeddingVector

sealed interface GroupTarget {
    data class Existing(val id: Int) : GroupTarget { init { require(id > 0) } }
    data object Created : GroupTarget
}

data class ResultingGroup(val target: GroupTarget, val componentIds: Set<Int>, val centroid: EmbeddingVector)

/** Supports create, attach, split and merge without exposing individual membership edits. */
data class GroupChange(
    val expectedGroups: Map<Int, Long>,
    val incomingComponentIds: Set<Int>,
    val resultingGroups: List<ResultingGroup>,
    val embeddings: Map<Int, Embedding<ComponentEmbeddingKey>>,
) {
    init {
        require(expectedGroups.all { (id, revision) -> id > 0 && revision > 0 })
        require(incomingComponentIds.all { it > 0 })
        ComponentPartition(resultingGroups.map { it.componentIds })
        val retainedIds = resultingGroups.mapNotNull { (it.target as? GroupTarget.Existing)?.id }
        require(retainedIds.distinct().size == retainedIds.size)
        require(retainedIds.all { it in expectedGroups })
        require(embeddings.keys == resultingGroups.flatMap { it.componentIds }.toSet())
        require(embeddings.all { (id, embedding) -> id == embedding.key.componentId })
    }
}
