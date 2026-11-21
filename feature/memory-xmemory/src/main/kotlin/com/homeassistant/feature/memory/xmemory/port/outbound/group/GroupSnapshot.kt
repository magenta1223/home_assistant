package com.homeassistant.feature.memory.xmemory.port.outbound.group

import com.homeassistant.feature.memory.xmemory.port.outbound.embedding.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.domain.memory.xmemory.group.Group
import com.homeassistant.domain.embedding.EmbeddingVector

data class StoredGroup(val group: Group, val revision: Long, val centroid: EmbeddingVector) {
    init { require(revision > 0) }
}

data class GroupSnapshot(
    val groups: List<StoredGroup>,
    val embeddings: Map<Int, Embedding<ComponentEmbeddingKey>>,
)
