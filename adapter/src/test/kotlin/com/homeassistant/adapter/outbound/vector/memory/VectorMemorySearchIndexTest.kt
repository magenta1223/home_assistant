package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.application.memory.answer.MemorySearchDocument
import com.homeassistant.application.memory.answer.MemorySearchHit
import com.homeassistant.application.memory.answer.MemorySearchTopic
import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.vector.VectorPoint
import com.homeassistant.adapter.outbound.vector.VectorSearchFilter
import com.homeassistant.adapter.outbound.vector.VectorSearchResult
import com.homeassistant.adapter.outbound.vector.VectorStore
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

class VectorMemorySearchIndexTest {
    @Test
    fun `indexes one canonical memory with topic context`() {
        val textEmbedder = RecordingTextEmbedder()
        val vectorStore = RecordingVectorStore()
        val index = VectorMemorySearchIndex(textEmbedder, vectorStore)

        index.index(document(memory(11, "차단기 리모컨은 벽장 제일 위칸에 있다.")))

        assertEquals(
            listOf("passage: 집 물건 위치\n리모컨 위치 정보\n차단기 리모컨은 벽장 제일 위칸에 있다."),
            textEmbedder.embeddedTexts,
        )
        assertEquals(1_000_000_011, vectorStore.upserted.single().id)
        assertEquals("memory", vectorStore.upserted.single().payload["kind"])
        assertEquals("7", vectorStore.upserted.single().payload["topicId"])
        assertEquals("11", vectorStore.upserted.single().payload["memoryId"])
        assertEquals(TEST_USER.value, vectorStore.upserted.single().payload["createdByUserId"])
        assertEquals("FAMILY", vectorStore.upserted.single().payload["visibility"])
        assertEquals("family-kakao.txt", vectorStore.upserted.single().payload["sourceName"])
    }

    @Test
    fun `searches only canonical memory vectors and maps ids`() {
        val textEmbedder = RecordingTextEmbedder()
        val vectorStore = RecordingVectorStore(
            searchResults = listOf(
                VectorSearchResult(
                    id = 1_000_000_011,
                    score = 0.94,
                    payload = mapOf("topicId" to "7", "memoryId" to "11"),
                ),
                VectorSearchResult(
                    id = 1_000_000_012,
                    score = 0.88,
                    payload = mapOf("topicId" to "8", "memoryId" to "12"),
                ),
            ),
        )
        val index = VectorMemorySearchIndex(textEmbedder, vectorStore)

        val hits = index.search(TEST_USER, "차단기 리모컨 어디 있어?", limit = 5)

        assertEquals(listOf("query: 차단기 리모컨 어디 있어?"), textEmbedder.embeddedTexts)
        assertEquals(
            VectorSearchFilter(must = mapOf("kind" to "memory")),
            vectorStore.lastFilter,
        )
        assertEquals(5, vectorStore.lastLimit)
        assertEquals(
            listOf(
                MemorySearchHit(topicId = 7, memoryId = 11, score = 0.94),
                MemorySearchHit(topicId = 8, memoryId = 12, score = 0.88),
            ),
            hits,
        )
    }

    @Test
    fun `preserves standalone memory without topic id`() {
        val vectorStore = RecordingVectorStore(
            searchResults = listOf(
                VectorSearchResult(
                    id = 1_000_000_011,
                    score = 0.94,
                    payload = mapOf("memoryId" to "11"),
                ),
            ),
        )
        val index = VectorMemorySearchIndex(RecordingTextEmbedder(), vectorStore)

        index.index(MemorySearchDocument(memory(11, "독립 기억", topicId = null)))
        val hits = index.search(TEST_USER, "독립 기억", limit = 5)

        assertEquals(null, vectorStore.upserted.single().payload["topicId"])
        assertEquals(MemorySearchHit(topicId = null, memoryId = 11, score = 0.94), hits.single())
    }
}

private class RecordingTextEmbedder : TextEmbedder {
    val embeddedTexts = mutableListOf<String>()

    override fun embed(text: String): List<Float> {
        embeddedTexts += text
        return listOf(0.1f, 0.2f, 0.3f)
    }
}

private class RecordingVectorStore(
    private val searchResults: List<VectorSearchResult> = emptyList(),
) : VectorStore {
    val upserted = mutableListOf<VectorPoint>()
    var lastFilter: VectorSearchFilter? = null
    var lastLimit: Int? = null

    override fun upsert(point: VectorPoint) {
        upserted += point
    }

    override fun search(
        vector: List<Float>,
        filter: VectorSearchFilter,
        limit: Int,
    ): List<VectorSearchResult> {
        lastFilter = filter
        lastLimit = limit
        return searchResults
    }
}

private fun document(memory: Memory) =
    MemorySearchDocument(
        memory = memory,
        topic = MemorySearchTopic(
            title = "집 물건 위치",
            summary = "리모컨 위치 정보",
            source = SourceDescriptor("kakao", "family-kakao.txt"),
        ),
    )

private fun memory(id: Int, content: String, topicId: Int? = 7) =
    Memory(
        id = id,
        topicId = topicId,
        createdByUserId = TEST_USER.value,
        content = content,
        subject = "리모컨",
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.SAID,
        visibility = MemoryVisibility.FAMILY,
        evidenceRefs = listOf(10),
    )

private val TEST_USER = UserId("dad")
