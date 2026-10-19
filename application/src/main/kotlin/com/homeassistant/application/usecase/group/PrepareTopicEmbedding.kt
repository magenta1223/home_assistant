package com.homeassistant.application.usecase.group

import com.homeassistant.application.port.output.group.*

class PrepareTopicEmbedding(
    private val generator: TopicEmbeddingGenerator,
    private val store: TopicEmbeddingStore,
) {
    val space: EmbeddingSpace get() = generator.space

    fun prepare(topic: PendingTopic): TopicEmbedding {
        val key = topic.embeddingKey(space)
        return store.find(key) ?: store.save(TopicEmbedding(key, generator.generate(topic.description)))
    }
}
