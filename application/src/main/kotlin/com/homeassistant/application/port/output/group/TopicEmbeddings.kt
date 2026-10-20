package com.homeassistant.application.port.output.group

import com.homeassistant.domain.group.TopicVector
import java.security.MessageDigest

data class EmbeddingSpace(val model: String, val inputFormat: String) {
    init { require(model.isNotBlank() && inputFormat.isNotBlank()) }
}

/** A Topic at one [version] of its vector; a new version means the vector changed and must be prepared again. */
data class PendingTopic(val id: Int, val version: String) {
    init { require(id > 0 && version.isNotBlank()) }
    fun embeddingKey(space: EmbeddingSpace) = TopicEmbeddingKey(id, version, space)
}

fun descriptionFingerprint(description: String): String =
    MessageDigest.getInstance("SHA-256").digest(description.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

data class TopicEmbeddingKey(val topicId: Int, val version: String, val space: EmbeddingSpace) {
    init { require(topicId > 0 && version.isNotBlank()) }
}

data class TopicEmbedding(val key: TopicEmbeddingKey, val vector: TopicVector)

interface PendingTopicSource {
    fun pending(limit: Int): List<PendingTopic>
}

interface TopicEmbeddingGenerator {
    val space: EmbeddingSpace
    /** Fails when [PendingTopic.version] is no longer the Topic's current version. */
    fun generate(topic: PendingTopic): TopicVector
}

interface TopicEmbeddingStore {
    fun find(key: TopicEmbeddingKey): TopicEmbedding?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: TopicEmbedding): TopicEmbedding
}

class GroupRebuildRequired(message: String) : IllegalStateException(message)
