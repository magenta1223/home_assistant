package com.homeassistant.adapter.inbound.tool

import com.homeassistant.application.memory.MemoryUseCasesFactory
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.inbound.tool.*
import com.homeassistant.domain.memory.EmbeddingService
import com.homeassistant.domain.memory.MemoryStore
import com.homeassistant.domain.memory.VectorStore
import com.homeassistant.domain.indexing.IndexingOutboxStore

interface MemoryToolExecutor : IToolExecutor {
    fun tools(): List<Tool>
}

internal class MemoryToolRegistry(
    memoryStore: MemoryStore,
    embeddingService: EmbeddingService,
    vectorStore: VectorStore,
    indexingOutbox: IndexingOutboxStore,
) : MemoryToolExecutor {

    private val memoryTools = MemoryTools(
        MemoryUseCasesFactory.create(memoryStore, embeddingService, vectorStore, indexingOutbox),
    )

    private val dispatch: Set<String> = memoryTools.tools.map { it.name }.toSet()

    override fun tools(): List<Tool> = memoryTools.tools

    override suspend fun execute(spec: ToolCallSpec, userId: UserId): ToolResult =
        if (spec.name in dispatch) memoryTools.execute(spec, userId)
        else error("Unhandled tool: ${spec.name}")
}

object MemoryToolExecutorFactory {
    fun create(
        memoryStore: MemoryStore,
        embeddingService: EmbeddingService,
        vectorStore: VectorStore,
        indexingOutbox: IndexingOutboxStore,
    ): MemoryToolExecutor =
        MemoryToolRegistry(memoryStore, embeddingService, vectorStore, indexingOutbox)
}
