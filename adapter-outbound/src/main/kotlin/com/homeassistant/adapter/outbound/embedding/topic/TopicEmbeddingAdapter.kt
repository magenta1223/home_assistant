package com.homeassistant.adapter.outbound.embedding.topic

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.group.PendingTopic
import com.homeassistant.application.port.output.group.TopicEmbeddingGenerator
import com.homeassistant.application.port.output.group.descriptionFingerprint
import com.homeassistant.application.port.output.topic.TopicCatalog
import com.homeassistant.domain.group.TopicVector

/** A Topic's vector is the embedding of its description; the description hash is its version. */
class TopicEmbeddingAdapter(
    private val embedder: TextEmbedder,
    model: String,
    private val catalog: TopicCatalog,
) : TopicEmbeddingGenerator {
    override val space = EmbeddingSpace(model, "topic-description:passage:v1")
    override fun generate(topic: PendingTopic): TopicVector {
        val description = requireNotNull(catalog.topics().singleOrNull { it.topicId == topic.id }) { "topic missing" }.description
        require(descriptionFingerprint(description) == topic.version) { "topic description changed" }
        return TopicVector.normalized(embedder.embed("passage: $description").map { it.toDouble() })
    }
}
