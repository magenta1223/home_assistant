package com.homeassistant.application.port.output.group

import com.homeassistant.domain.group.ComponentVector
import java.security.MessageDigest

data class EmbeddingSpace(val model: String, val inputFormat: String) {
    init { require(model.isNotBlank() && inputFormat.isNotBlank()) }
}

/** A Component at one [version] of its vector; a new version means the vector changed and must be prepared again. */
data class PendingComponent(val id: Int, val version: String) {
    init { require(id > 0 && version.isNotBlank()) }
    fun embeddingKey(space: EmbeddingSpace) = ComponentEmbeddingKey(id, version, space)
}

fun descriptionFingerprint(description: String): String =
    MessageDigest.getInstance("SHA-256").digest(description.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

data class ComponentEmbeddingKey(val componentId: Int, val version: String, val space: EmbeddingSpace) {
    init { require(componentId > 0 && version.isNotBlank()) }
}

data class ComponentEmbedding(val key: ComponentEmbeddingKey, val vector: ComponentVector)

interface PendingComponentSource {
    fun pending(limit: Int): List<PendingComponent>
}

interface ComponentEmbeddingGenerator {
    val space: EmbeddingSpace
    /** The vector of [component]; the store rejects it if the Component is no longer at [PendingComponent.version]. */
    fun generate(component: PendingComponent): ComponentVector
}

interface ComponentEmbeddingStore {
    fun find(key: ComponentEmbeddingKey): ComponentEmbedding?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: ComponentEmbedding): ComponentEmbedding
}

class GroupRebuildRequired(message: String) : IllegalStateException(message)
