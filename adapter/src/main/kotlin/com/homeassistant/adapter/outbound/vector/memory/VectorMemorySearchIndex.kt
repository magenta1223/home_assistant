package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.application.memory.answer.MemorySearchDocument
import com.homeassistant.application.memory.answer.MemorySearchHit
import com.homeassistant.application.memory.answer.MemorySearchIndex
import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.vector.VectorPoint
import com.homeassistant.adapter.outbound.vector.VectorSearchFilter
import com.homeassistant.adapter.outbound.vector.VectorStore
import com.homeassistant.domain.identity.UserId

internal class VectorMemorySearchIndex(
    private val textEmbedder: TextEmbedder,
    private val vectorStore: VectorStore,
) : MemorySearchIndex {
    override fun index(document: MemorySearchDocument) {
        val memory = document.memory
        vectorStore.upsert(
            VectorPoint(
                id = pointId(memory.id),
                vector = textEmbedder.embed(
                    "passage: " + listOfNotNull(
                        document.topic?.title,
                        document.topic?.summary,
                        memory.content,
                    ).joinToString("\n"),
                ),
                payload = buildMap {
                    put("kind", MEMORY_KIND)
                    put("memoryId", memory.id.toString())
                    memory.topicId?.let { put("topicId", it.toString()) }
                    put("createdByUserId", memory.createdByUserId)
                    put("visibility", memory.visibility.name)
                    document.topic?.source?.let { source ->
                        put("sourceType", source.type)
                        put("sourceName", source.name)
                    }
                    put("memoryType", memory.memoryType.code)
                    put("subject", memory.subject)
                },
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
                vector = textEmbedder.embed("query: $question"),
                filter = VectorSearchFilter(must = mapOf("kind" to MEMORY_KIND)),
                limit = limit.coerceIn(1, 10),
            )
            .mapNotNull { result ->
                val topicId = result.payload["topicId"]?.toIntOrNull()
                val memoryId = result.payload["memoryId"]?.toIntOrNull()
                if (memoryId == null) null
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
        textEmbedder: TextEmbedder,
        vectorStore: VectorStore,
    ): MemorySearchIndex =
        VectorMemorySearchIndex(textEmbedder, vectorStore)
}
