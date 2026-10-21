package com.homeassistant.adapter.outbound.embedding.memory

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.application.port.output.topic.QueryEmbeddingGenerator
import com.homeassistant.domain.group.TopicVector

/** Same model as [MemoryEmbeddingAdapter]; e5 models expect `query: ` for questions and `passage: ` for stored text. */
class QueryEmbeddingAdapter(private val embedder: TextEmbedder) : QueryEmbeddingGenerator {
    override fun generate(query: String): TopicVector =
        TopicVector.normalized(embedder.embed("query: $query").map { it.toDouble() })
}
