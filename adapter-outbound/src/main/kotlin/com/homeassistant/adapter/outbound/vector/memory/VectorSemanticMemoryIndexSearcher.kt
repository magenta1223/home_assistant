package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.vector.VectorSearchFilter
import com.homeassistant.adapter.outbound.vector.VectorStore
import com.homeassistant.application.memory.search.SemanticMemoryIndexSearcher
import com.homeassistant.application.memory.search.SemanticMemorySearchHit

internal class VectorSemanticMemoryIndexSearcher(
    private val textEmbedder: TextEmbedder,
    private val vectorStore: VectorStore,
) : SemanticMemoryIndexSearcher {
    override fun search(query: String, limit: Int): List<SemanticMemorySearchHit> =
        vectorStore.search(
            vector = textEmbedder.embed("query: $query"),
            filter = VectorSearchFilter(must = mapOf("kind" to MEMORY_KIND)),
            limit = limit.coerceIn(1, 10),
        ).mapNotNull { result ->
            result.payload["memoryId"]?.toIntOrNull()?.let { memoryId ->
                SemanticMemorySearchHit(memoryId, result.score)
            }
        }
}

object SemanticMemoryIndexSearcherFactory {
    fun create(textEmbedder: TextEmbedder, vectorStore: VectorStore): SemanticMemoryIndexSearcher =
        VectorSemanticMemoryIndexSearcher(textEmbedder, vectorStore)
}
