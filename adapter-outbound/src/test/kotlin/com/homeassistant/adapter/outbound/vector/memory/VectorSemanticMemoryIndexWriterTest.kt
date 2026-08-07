package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.vector.*
import com.homeassistant.application.memory.CanonicalMemoryContext
import com.homeassistant.application.memory.MemoryTopicContext
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.*
import com.homeassistant.domain.source.SourceDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

class VectorSemanticMemoryIndexWriterTest {
    @Test
    fun `indexes canonical memory with topic context`() {
        val embedder = RecordingTextEmbedder()
        val store = RecordingVectorStore()
        val writer = VectorSemanticMemoryIndexWriter(embedder, store)

        writer.upsert(context(memory(11, "차단기 리모컨은 벽장 제일 위칸에 있다.")))

        assertEquals(listOf("passage: 집 물건 위치\n리모컨 위치 정보\n차단기 리모컨은 벽장 제일 위칸에 있다."), embedder.texts)
        assertEquals("7", store.points.single().payload["topicId"])
        assertEquals("11", store.points.single().payload["memoryId"])
        assertEquals("family-kakao.txt", store.points.single().payload["sourceName"])
    }

    @Test
    fun `indexes standalone memory without fake topic id`() {
        val store = RecordingVectorStore()
        val writer = VectorSemanticMemoryIndexWriter(RecordingTextEmbedder(), store)

        writer.upsert(CanonicalMemoryContext(memory(11, "독립 기억", topicId = null)))

        assertEquals(null, store.points.single().payload["topicId"])
        assertEquals("11", store.points.single().payload["memoryId"])
    }
}

private class RecordingTextEmbedder : TextEmbedder {
    val texts = mutableListOf<String>()
    override fun embed(text: String): List<Float> = listOf(0.1f).also { texts += text }
}

private class RecordingVectorStore : VectorStore {
    val points = mutableListOf<VectorPoint>()
    override fun upsert(point: VectorPoint) { points += point }
    override fun search(vector: List<Float>, filter: VectorSearchFilter, limit: Int): List<VectorSearchResult> = emptyList()
}

private fun context(memory: Memory) = CanonicalMemoryContext(
    memory,
    MemoryTopicContext(7, "집 물건 위치", "리모컨 위치 정보", SourceDescriptor("kakao", "family-kakao.txt")),
)

private fun memory(id: Int, content: String, topicId: Int? = 7) = Memory(
    id, topicId, UserId("dad").value, content, "리모컨", MemoryType.REFERENCE,
    MemoryCertainty.SAID, MemoryVisibility.FAMILY, listOf(10),
)
