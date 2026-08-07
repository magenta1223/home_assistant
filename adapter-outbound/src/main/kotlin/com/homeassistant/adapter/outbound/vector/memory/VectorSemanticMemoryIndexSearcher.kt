package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.vector.VectorSearchFilter
import com.homeassistant.adapter.outbound.vector.VectorStore
import com.homeassistant.application.memory.read.SemanticMemoryIndexSearcher
import com.homeassistant.application.memory.read.MemoryIndex
import com.homeassistant.application.memory.read.MemoryIndexSearchScope

internal class VectorSemanticMemoryIndexSearcher(
    private val textEmbedder: TextEmbedder,
    private val vectorStore: VectorStore,
) : SemanticMemoryIndexSearcher {
    override fun search(query: String, limit: Int): List<MemoryIndex> =
        search(query, limit, MemoryIndexSearchScope())

    override fun search(
        query: String,
        limit: Int,
        scope: MemoryIndexSearchScope,
    ): List<MemoryIndex> =
        vectorStore.search(
            vector = textEmbedder.embed("query: $query"),
            filter = VectorSearchFilter(
                must = buildMap {
                    put("kind", MEMORY_KIND)
                },
                ids = scope.allowedMemoryIds.orEmpty().map(::memoryVectorPointId).toSet(),
            ),
            limit = limit.coerceIn(1, 10),
        ).mapNotNull { result ->
            result.payload["memoryId"]?.toIntOrNull()?.let { memoryId ->
                MemoryIndex(memoryId, result.score)
            }
        }
}

object SemanticMemoryIndexSearcherFactory {
    fun create(textEmbedder: TextEmbedder, vectorStore: VectorStore): SemanticMemoryIndexSearcher =
        VectorSemanticMemoryIndexSearcher(textEmbedder, vectorStore)
}
