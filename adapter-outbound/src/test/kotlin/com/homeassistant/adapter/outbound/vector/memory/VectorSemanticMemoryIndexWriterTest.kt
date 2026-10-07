package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.vector.VectorPoint
import com.homeassistant.adapter.outbound.vector.VectorSearchFilter
import com.homeassistant.adapter.outbound.vector.VectorSearchResult
import com.homeassistant.adapter.outbound.vector.VectorStore
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorSemanticMemoryIndexWriterTest {
    @Test
    fun `index embeds complete content without copying canonical metadata to payload`() {
        val inputs = mutableListOf<String>()
        val embedder = object : TextEmbedder {
            override fun embed(text: String): List<Float> {
                inputs += text
                return listOf(0.25f, 0.5f)
            }
        }
        val points = mutableListOf<VectorPoint>()
        val store = object : VectorStore {
            override fun upsert(point: VectorPoint) {
                points += point
            }

            override fun search(
                vector: List<Float>,
                filter: VectorSearchFilter,
                limit: Int,
            ): List<VectorSearchResult> = error("unused")
        }
        val memory = Memory(
            id = 42,
            createdByUserId = "member-1",
            content = "연구자는 센서 오류일 가능성이 있다고 말했다.",
            visibility = MemoryVisibility.RESTRICTED,
            allowedUserIds = setOf("member-1"),
            evidenceRefs = listOf(7),
            createdAt = 1_000L,
        )

        assertTrue(VectorSemanticMemoryIndexWriter(embedder, store).upsert(memory))

        assertEquals(listOf("passage: ${memory.content}"), inputs)
        val point = points.single()
        assertEquals(memoryVectorPointId(memory.id), point.id)
        assertEquals(listOf(0.25f, 0.5f), point.vector)
        assertEquals(mapOf("kind" to "memory", "memoryId" to "42"), point.payload)
    }
}
