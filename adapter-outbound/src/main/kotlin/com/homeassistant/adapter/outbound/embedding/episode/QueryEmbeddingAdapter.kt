package com.homeassistant.adapter.outbound.embedding.episode

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.application.port.output.component.QueryEmbeddingGenerator
import com.homeassistant.domain.group.ComponentVector

/** Same model as [EpisodeEmbeddingAdapter]; e5 models expect `query: ` for questions and `passage: ` for stored text. */
class QueryEmbeddingAdapter(private val embedder: TextEmbedder) : QueryEmbeddingGenerator {
    override fun generate(query: String): ComponentVector =
        ComponentVector.normalized(embedder.embed("query: $query").map { it.toDouble() })
}
