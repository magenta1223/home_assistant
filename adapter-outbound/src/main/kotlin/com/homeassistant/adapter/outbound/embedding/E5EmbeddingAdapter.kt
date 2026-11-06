package com.homeassistant.adapter.outbound.embedding

import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.domain.group.ComponentVector

/** An e5 model behind [embedder]; e5 expects `passage: ` before stored text and `query: ` before questions. */
class E5EmbeddingAdapter(
    private val embedder: TextEmbedder,
    model: String,
    private val runtime: EmbeddingServerRuntime,
) : EmbeddingGenerator {
    override val space = EmbeddingSpace(model, "episode-content:passage:v1")

    override fun isAvailable(): Boolean = runtime.isReady

    override fun embedDocument(text: String): ComponentVector = embed("passage: $text")

    override fun embedQuery(text: String): ComponentVector = embed("query: $text")

    private fun embed(input: String): ComponentVector =
        ComponentVector.normalized(embedder.embed(input).map { it.toDouble() })
}
