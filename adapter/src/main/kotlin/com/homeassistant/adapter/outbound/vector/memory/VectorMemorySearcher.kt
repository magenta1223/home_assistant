package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.vector.VectorSearchFilter
import com.homeassistant.adapter.outbound.vector.VectorStore
import com.homeassistant.application.memory.search.MemorySearchHit
import com.homeassistant.application.memory.search.MemorySearcher

internal class VectorMemorySearcher(
    private val textEmbedder: TextEmbedder,
    private val vectorStore: VectorStore,
) : MemorySearcher {
    override fun search(query: String, limit: Int): List<MemorySearchHit> =
        vectorStore.search(
            vector = textEmbedder.embed("query: $query"),
            filter = VectorSearchFilter(must = mapOf("kind" to MEMORY_KIND)),
            limit = limit.coerceIn(1, 10),
        ).mapNotNull { result ->
            result.payload["memoryId"]?.toIntOrNull()?.let { memoryId ->
                MemorySearchHit(memoryId, result.score)
            }
        }
}

object MemorySearcherFactory {
    fun create(textEmbedder: TextEmbedder, vectorStore: VectorStore): MemorySearcher =
        VectorMemorySearcher(textEmbedder, vectorStore)
}
