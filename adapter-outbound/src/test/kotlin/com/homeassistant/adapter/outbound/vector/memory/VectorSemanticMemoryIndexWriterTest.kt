package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.vector.*
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.*
import kotlin.test.Test
import kotlin.test.assertEquals

class VectorSemanticMemoryIndexWriterTest {
    @Test
    fun `indexes memory without parent context`() {
        val embedder = RecordingTextEmbedder()
        val store = RecordingVectorStore()
        val writer = VectorSemanticMemoryIndexWriter(embedder, store)

        writer.upsert(memory(11, "차단기 리모컨은 벽장 제일 위칸에 있다."))

        assertEquals(
            listOf("passage: 리모컨\n차단기 리모컨은 벽장 제일 위칸에 있다."),
            embedder.texts,
        )
        assertEquals(null, store.points.single().payload["parentId"])
        assertEquals("11", store.points.single().payload["memoryId"])
    }

    @Test
    fun `indexes parent id when memory is placed`() {
        val store = RecordingVectorStore()
        val writer = VectorSemanticMemoryIndexWriter(RecordingTextEmbedder(), store)

        writer.upsert(memory(12, "리모컨은 서랍에 있다.").copy(parentId = 7))

        assertEquals("7", store.points.single().payload["parentId"])
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

private fun memory(id: Int, content: String) = Memory(
    id = id,
    parentId = null,
    createdByUserId = UserId("dad").value,
    content = content,
    subject = "리모컨",
    memoryType = MemoryType.REFERENCE,
    certainty = MemoryCertainty.SAID,
    visibility = MemoryVisibility.FAMILY,
    evidenceRefs = listOf(10),
)
