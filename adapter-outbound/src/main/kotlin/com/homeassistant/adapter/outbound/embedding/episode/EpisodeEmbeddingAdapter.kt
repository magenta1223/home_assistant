package com.homeassistant.adapter.outbound.embedding.episode

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.application.port.outbound.group.EmbeddingSpace
import com.homeassistant.application.port.outbound.component.EpisodeEmbeddingGenerator
import com.homeassistant.domain.group.ComponentVector

class EpisodeEmbeddingAdapter(
    private val embedder: TextEmbedder,
    model: String,
    private val runtime: EmbeddingServerRuntime,
) : EpisodeEmbeddingGenerator {
    override val space = EmbeddingSpace(model, "episode-content:passage:v1")
    override fun isAvailable(): Boolean = runtime.isReady
    override fun generate(content: String): ComponentVector =
        ComponentVector.normalized(embedder.embed("passage: $content").map { it.toDouble() })
}
