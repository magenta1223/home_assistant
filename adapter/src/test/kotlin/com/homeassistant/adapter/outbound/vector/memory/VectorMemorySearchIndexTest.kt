package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.application.topicanswer.answer.MemorySearchDocument
import com.homeassistant.application.topicanswer.answer.MemorySearchHit
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.EmbeddingService
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.memory.PayloadVectorPoint
import com.homeassistant.domain.memory.PayloadVectorSearchFilter
import com.homeassistant.domain.memory.PayloadVectorSearchResult
import com.homeassistant.domain.memory.PayloadVectorStore
import kotlin.test.Test
import kotlin.test.assertEquals

class VectorMemorySearchIndexTest {
    @Test
    fun `indexes one canonical memory with topic context`() {
        val embeddingService = RecordingEmbeddingService()
        val vectorStore = RecordingPayloadVectorStore()
        val index = VectorMemorySearchIndex(embeddingService, vectorStore)

        index.index(document(memory(11, "차단기 리모컨은 벽장 제일 위칸에 있다.")))

        assertEquals(
            listOf("passage: 집 물건 위치\n리모컨 위치 정보\n차단기 리모컨은 벽장 제일 위칸에 있다."),
            embeddingService.embeddedTexts,
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
        val embeddingService = RecordingEmbeddingService()
        val vectorStore = RecordingPayloadVectorStore(
            searchResults = listOf(
                PayloadVectorSearchResult(
                    id = 1_000_000_011,
                    score = 0.94,
                    payload = mapOf("topicId" to "7", "memoryId" to "11"),
                ),
                PayloadVectorSearchResult(
                    id = 1_000_000_012,
                    score = 0.88,
                    payload = mapOf("topicId" to "8", "memoryId" to "12"),
                ),
            ),
        )
        val index = VectorMemorySearchIndex(embeddingService, vectorStore)

        val hits = index.search(TEST_USER, "차단기 리모컨 어디 있어?", limit = 5)

        assertEquals(listOf("query: 차단기 리모컨 어디 있어?"), embeddingService.embeddedTexts)
        assertEquals(
            PayloadVectorSearchFilter(must = mapOf("kind" to "memory")),
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
}

private class RecordingEmbeddingService : EmbeddingService {
    val embeddedTexts = mutableListOf<String>()

    override fun embed(text: String): List<Float> {
        embeddedTexts += text
        return listOf(0.1f, 0.2f, 0.3f)
    }
}

private class RecordingPayloadVectorStore(
    private val searchResults: List<PayloadVectorSearchResult> = emptyList(),
) : PayloadVectorStore {
    val upserted = mutableListOf<PayloadVectorPoint>()
    var lastFilter: PayloadVectorSearchFilter? = null
    var lastLimit: Int? = null

    override fun upsert(point: PayloadVectorPoint) {
        upserted += point
    }

    override fun search(
        vector: List<Float>,
        filter: PayloadVectorSearchFilter,
        limit: Int,
    ): List<PayloadVectorSearchResult> {
        lastFilter = filter
        lastLimit = limit
        return searchResults
    }
}

private fun document(memory: Memory) =
    MemorySearchDocument(
        memory = memory,
        topicTitle = "집 물건 위치",
        topicSummary = "리모컨 위치 정보",
        sourceType = "kakao",
        sourceName = "family-kakao.txt",
    )

private fun memory(id: Int, content: String) =
    Memory(
        id = id,
        topicId = 7,
        createdByUserId = TEST_USER.value,
        content = content,
        subject = "리모컨",
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.SAID,
        visibility = MemoryVisibility.FAMILY,
        evidenceRefs = listOf(10),
    )

private val TEST_USER = UserId("dad")
