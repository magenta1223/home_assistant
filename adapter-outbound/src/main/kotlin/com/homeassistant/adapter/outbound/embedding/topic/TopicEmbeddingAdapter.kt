package com.homeassistant.adapter.outbound.embedding.topic

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.group.TopicEmbeddingGenerator
import com.homeassistant.domain.group.TopicVector

class TopicEmbeddingAdapter(private val embedder: TextEmbedder, model: String) : TopicEmbeddingGenerator {
    override val space = EmbeddingSpace(model, "topic-description:passage:v1")
    override fun generate(description: String): TopicVector =
        TopicVector.normalized(embedder.embed("passage: $description").map { it.toDouble() })
}
