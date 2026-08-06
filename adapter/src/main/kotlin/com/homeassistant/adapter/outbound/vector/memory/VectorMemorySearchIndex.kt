package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.application.topicanswer.answer.MemorySearchDocument
import com.homeassistant.application.topicanswer.answer.MemorySearchHit
import com.homeassistant.application.topicanswer.answer.MemorySearchIndex
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.EmbeddingService
import com.homeassistant.domain.memory.PayloadVectorPoint
import com.homeassistant.domain.memory.PayloadVectorSearchFilter
import com.homeassistant.domain.memory.PayloadVectorStore

internal class VectorMemorySearchIndex(
    private val embeddingService: EmbeddingService,
    private val vectorStore: PayloadVectorStore,
) : MemorySearchIndex {
    override fun index(document: MemorySearchDocument) {
        val memory = document.memory
        vectorStore.upsert(
            PayloadVectorPoint(
                id = pointId(memory.id),
                vector = embeddingService.embed(
                    "passage: ${document.topicTitle}\n${document.topicSummary}\n${memory.content}",
                ),
                payload = mapOf(
                    "kind" to MEMORY_KIND,
                    "memoryId" to memory.id.toString(),
                    "topicId" to memory.topicId.toString(),
                    "createdByUserId" to memory.createdByUserId,
                    "visibility" to memory.visibility.name,
                    "sourceType" to document.sourceType,
                    "sourceName" to document.sourceName,
                    "memoryType" to memory.memoryType.code,
                    "subject" to memory.subject,
                ),
            ),
        )
    }

    override fun search(
        userId: UserId,
        question: String,
        limit: Int,
    ): List<MemorySearchHit> =
        vectorStore
            .search(
                vector = embeddingService.embed("query: $question"),
                filter = PayloadVectorSearchFilter(must = mapOf("kind" to MEMORY_KIND)),
                limit = limit.coerceIn(1, 10),
            )
            .mapNotNull { result ->
                val topicId = result.payload["topicId"]?.toIntOrNull()
                val memoryId = result.payload["memoryId"]?.toIntOrNull()
                if (topicId == null || memoryId == null) null
                else MemorySearchHit(topicId = topicId, memoryId = memoryId, score = result.score)
            }

    private fun pointId(memoryId: Int): Int {
        require(memoryId in 0 until POINT_ID_NAMESPACE) {
            "Memory vector point id is out of range: memoryId=$memoryId"
        }
        return POINT_ID_NAMESPACE + memoryId
    }

    private companion object {
        const val MEMORY_KIND = "memory"
        const val POINT_ID_NAMESPACE = 1_000_000_000
    }
}

object MemorySearchIndexFactory {
    fun create(
        embeddingService: EmbeddingService,
        vectorStore: PayloadVectorStore,
    ): MemorySearchIndex =
        VectorMemorySearchIndex(embeddingService, vectorStore)
}
