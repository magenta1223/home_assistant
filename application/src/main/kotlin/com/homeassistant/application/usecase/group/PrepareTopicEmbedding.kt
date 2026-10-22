package com.homeassistant.application.usecase.group

import com.homeassistant.application.port.output.group.*

/** Provides a Topic's vector for Group organization, generating and storing it only when its version is not stored yet. */
class PrepareTopicEmbedding(
    private val generator: TopicEmbeddingGenerator,
    private val store: TopicEmbeddingStore,
) {
    /** The model and input format every prepared vector belongs to. */
    val space: EmbeddingSpace get() = generator.space

    /** The stored vector of [topic]'s version in [space], generated and stored first when missing. */
    fun prepare(topic: PendingTopic): TopicEmbedding {
        val key = topic.embeddingKey(space)
        return store.find(key) ?: store.save(TopicEmbedding(key, generator.generate(topic)))
    }
}
