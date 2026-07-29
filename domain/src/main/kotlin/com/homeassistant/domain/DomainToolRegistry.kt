package com.homeassistant.domain

import com.homeassistant.core.identity.UserId
import com.homeassistant.core.tools.*
import com.homeassistant.domain.memory.EmbeddingService
import com.homeassistant.domain.memory.MemoryStore
import com.homeassistant.domain.memory.MemoryTools
import com.homeassistant.domain.memory.VectorStore
import com.homeassistant.domain.indexing.IndexingOutboxStore

interface DomainTools : IToolExecutor {
    fun tools(): List<Tool>
}

internal class DomainToolRegistry(
    memoryStore: MemoryStore,
    embeddingService: EmbeddingService,
    vectorStore: VectorStore,
    indexingOutbox: IndexingOutboxStore,
) : DomainTools {

    private val memoryTools = MemoryTools(memoryStore, embeddingService, vectorStore, indexingOutbox)

    private val dispatch: Set<String> = memoryTools.tools.map { it.name }.toSet()

    override fun tools(): List<Tool> = memoryTools.tools

    override suspend fun execute(spec: ToolCallSpec, userId: UserId): ToolResult =
        if (spec.name in dispatch) memoryTools.execute(spec, userId)
        else error("Unhandled tool: ${spec.name}")
}

object DomainToolsFactory {
    fun create(
        memoryStore: MemoryStore,
        embeddingService: EmbeddingService,
        vectorStore: VectorStore,
        indexingOutbox: IndexingOutboxStore,
    ): DomainTools =
        DomainToolRegistry(memoryStore, embeddingService, vectorStore, indexingOutbox)
}
