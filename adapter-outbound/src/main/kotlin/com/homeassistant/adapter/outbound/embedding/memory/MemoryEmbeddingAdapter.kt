package com.homeassistant.adapter.outbound.embedding.memory

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.topic.MemoryEmbeddingGenerator
import com.homeassistant.domain.group.TopicVector

class MemoryEmbeddingAdapter(
    private val embedder: TextEmbedder,
    model: String,
    private val runtime: EmbeddingServerRuntime,
) : MemoryEmbeddingGenerator {
    override val space = EmbeddingSpace(model, "memory-content:passage:v1")
    override fun isAvailable(): Boolean = runtime.isReady
    override fun generate(content: String): TopicVector =
        TopicVector.normalized(embedder.embed("passage: $content").map { it.toDouble() })
}
