package com.homeassistant.application.port.output.group

import com.homeassistant.domain.group.TopicVector
import java.security.MessageDigest

data class EmbeddingSpace(val model: String, val inputFormat: String) {
    init { require(model.isNotBlank() && inputFormat.isNotBlank()) }
}

data class PendingTopic(val id: Int, val description: String) {
    init { require(id > 0 && description.isNotBlank()) }
    fun embeddingKey(space: EmbeddingSpace) = TopicEmbeddingKey(id, descriptionFingerprint(description), space)
}

fun descriptionFingerprint(description: String): String =
    MessageDigest.getInstance("SHA-256").digest(description.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

data class TopicEmbeddingKey(val topicId: Int, val descriptionHash: String, val space: EmbeddingSpace) {
    init { require(topicId > 0 && descriptionHash.isNotBlank()) }
}

data class TopicEmbedding(val key: TopicEmbeddingKey, val vector: TopicVector)

interface PendingTopicSource {
    fun pending(limit: Int): List<PendingTopic>
}

interface TopicEmbeddingGenerator {
    val space: EmbeddingSpace
    fun generate(description: String): TopicVector
}

interface TopicEmbeddingStore {
    fun find(key: TopicEmbeddingKey): TopicEmbedding?
    /** Immutable per key. Concurrent preparation returns the first persisted vector. */
    fun save(embedding: TopicEmbedding): TopicEmbedding
}

class GroupRebuildRequired(message: String) : IllegalStateException(message)
