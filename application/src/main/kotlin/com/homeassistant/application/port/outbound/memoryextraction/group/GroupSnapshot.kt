package com.homeassistant.application.port.outbound.memoryextraction.group

import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.domain.group.Group
import com.homeassistant.domain.embedding.EmbeddingVector

data class StoredGroup(val group: Group, val revision: Long, val centroid: EmbeddingVector) {
    init { require(revision > 0) }
}

data class GroupSnapshot(
    val groups: List<StoredGroup>,
    val embeddings: Map<Int, Embedding<ComponentEmbeddingKey>>,
)
