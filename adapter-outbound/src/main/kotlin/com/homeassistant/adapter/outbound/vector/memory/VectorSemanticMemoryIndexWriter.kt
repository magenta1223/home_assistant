package com.homeassistant.adapter.outbound.vector.memory

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.vector.VectorPoint
import com.homeassistant.adapter.outbound.vector.VectorStore
import com.homeassistant.application.port.output.memory.write.SemanticMemoryIndexWriter
import com.homeassistant.domain.memory.Memory
import org.slf4j.LoggerFactory

internal class VectorSemanticMemoryIndexWriter(
    private val textEmbedder: TextEmbedder,
    private val vectorStore: VectorStore,
) : SemanticMemoryIndexWriter {
    override fun upsert(memory: Memory): Boolean {
        return try {
            vectorStore.upsert(
                VectorPoint(
                    id = memoryVectorPointId(memory.id),
                    vector = textEmbedder.embed(
                        "passage: " + listOfNotNull(
                            memory.subject,
                            memory.content,
                        ).joinToString("\n"),
                    ),
                    payload = buildMap {
                        put("kind", MEMORY_KIND)
                        put("memoryId", memory.id.toString())
                        put("childrenIds", memory.childrenIds.joinToString(","))
                        put("createdByUserId", memory.createdByUserId)
                        put("visibility", memory.visibility.name)
                        put("allowedUserIds", memory.allowedUserIds.sorted().joinToString(","))
                        put("memoryType", memory.memoryType.name)
                        put("subject", memory.subject)
                    },
                ),
            )
            true
        } catch (e: Exception) {
            log.warn("Memory vector indexing deferred memoryId=${memory.id}", e)
            false
        }
    }

    private val log = LoggerFactory.getLogger(VectorSemanticMemoryIndexWriter::class.java)

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
