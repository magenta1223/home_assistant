package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.vector.VectorPoint
import com.homeassistant.adapter.outbound.vector.VectorStore
import com.homeassistant.application.memory.CanonicalMemoryContext
import com.homeassistant.application.memory.index.SemanticMemoryIndexWriter

internal class VectorSemanticMemoryIndexWriter(
    private val textEmbedder: TextEmbedder,
    private val vectorStore: VectorStore,
) : SemanticMemoryIndexWriter {
    override fun upsert(context: CanonicalMemoryContext) {
        val memory = context.memory
        vectorStore.upsert(
            VectorPoint(
                id = memoryVectorPointId(memory.id),
                vector = textEmbedder.embed(
                    "passage: " + listOfNotNull(
                        context.topic?.title,
                        context.topic?.summary,
                        memory.content,
                    ).joinToString("\n"),
                ),
                payload = buildMap {
                    put("kind", MEMORY_KIND)
                    put("memoryId", memory.id.toString())
                    memory.topicId?.let { put("topicId", it.toString()) }
                    put("createdByUserId", memory.createdByUserId)
                    put("visibility", memory.visibility.name)
                    context.topic?.source?.let { source ->
                        put("sourceType", source.type)
                        put("sourceName", source.name)
                    }
                    put("memoryType", memory.memoryType.name)
                    put("subject", memory.subject)
                },
            ),
        )
    }
}

object SemanticMemoryIndexWriterFactory {
    fun create(textEmbedder: TextEmbedder, vectorStore: VectorStore): SemanticMemoryIndexWriter =
        VectorSemanticMemoryIndexWriter(textEmbedder, vectorStore)
}

internal const val MEMORY_KIND = "memory"
private const val POINT_ID_NAMESPACE = 1_000_000_000

internal fun memoryVectorPointId(memoryId: Int): Int {
    require(memoryId in 0 until POINT_ID_NAMESPACE) {
        "Memory vector point id is out of range: memoryId=$memoryId"
    }
    return POINT_ID_NAMESPACE + memoryId
}
