package com.homeassistant.application.port.outbound.memoryextraction.group

import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace

/** A Component at one [version] of its vector; a new version means the vector changed and must be prepared again. */
data class PendingComponent(val id: Int, val version: String) {
    init { require(id > 0 && version.isNotBlank()) }
    fun embeddingKey(space: EmbeddingSpace) = ComponentEmbeddingKey(id, version, space)
}

data class ComponentEmbeddingKey(val componentId: Int, val version: String, val space: EmbeddingSpace) {
    init { require(componentId > 0 && version.isNotBlank()) }
}

class GroupRebuildRequired(message: String) : IllegalStateException(message)
