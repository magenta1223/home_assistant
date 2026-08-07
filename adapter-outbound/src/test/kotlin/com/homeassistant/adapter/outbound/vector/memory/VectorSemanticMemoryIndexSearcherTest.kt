package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.vector.*
import com.homeassistant.application.memory.io.MemoryIndex
import kotlin.test.Test
import kotlin.test.assertEquals

class VectorSemanticMemoryIndexSearcherTest {
    @Test
    fun `searches canonical memory vectors and maps only memory ids`() {
        val embedder = SearchTextEmbedder()
        val store = SearchVectorStore(
            listOf(VectorSearchResult(1_000_000_011, 0.94, mapOf("memoryId" to "11"))),
        )
        val searcher = VectorSemanticMemoryIndexSearcher(embedder, store)

        val hits = searcher.search("차단기 리모컨 어디 있어?", 5)

        assertEquals(listOf("query: 차단기 리모컨 어디 있어?"), embedder.texts)
        assertEquals(VectorSearchFilter(must = mapOf("kind" to "memory")), store.filter)
        assertEquals(listOf(MemoryIndex(11, 0.94)), hits)
    }
}

private class SearchTextEmbedder : TextEmbedder {
    val texts = mutableListOf<String>()
    override fun embed(text: String): List<Float> = listOf(0.1f).also { texts += text }
}

private class SearchVectorStore(private val results: List<VectorSearchResult>) : VectorStore {
    var filter: VectorSearchFilter? = null
    override fun upsert(point: VectorPoint) = Unit
    override fun search(vector: List<Float>, filter: VectorSearchFilter, limit: Int): List<VectorSearchResult> {
        this.filter = filter
        return results
    }
}
