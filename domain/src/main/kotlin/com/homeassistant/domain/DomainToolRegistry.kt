package com.homeassistant.domain

import com.homeassistant.core.identity.UserId
import com.homeassistant.core.tools.*
import com.homeassistant.domain.memory.EmbeddingService
import com.homeassistant.domain.memory.MemoryStore
import com.homeassistant.domain.memory.MemoryTools
import com.homeassistant.domain.memory.VectorStore

class DomainToolRegistry(
    memoryStore: MemoryStore,
    embeddingService: EmbeddingService,
    vectorStore: VectorStore,
) : IToolExecutor {

    private val memoryTools = MemoryTools(memoryStore, embeddingService, vectorStore)

    private val dispatch: Set<String> = memoryTools.tools.map { it.name }.toSet()

    fun tools(): List<Tool> = memoryTools.tools

    override suspend fun execute(spec: ToolCallSpec, userId: UserId): ToolResult =
        if (spec.name in dispatch) memoryTools.execute(spec, userId)
        else error("Unhandled tool: ${spec.name}")
}
