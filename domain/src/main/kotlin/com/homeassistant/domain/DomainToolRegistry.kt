package com.homeassistant.domain

import com.homeassistant.core.identity.UserId
import com.homeassistant.core.tools.*
import com.homeassistant.domain.memory.EmbeddingService
import com.homeassistant.domain.memory.MemoryRepository
import com.homeassistant.domain.memory.MemoryTools
import com.homeassistant.domain.memory.VectorStore
import org.jetbrains.exposed.sql.Database

class DomainToolRegistry(
    db: Database,
    embeddingService: EmbeddingService,
    vectorStore: VectorStore,
) : IToolExecutor {

    private val memoryTools = MemoryTools(MemoryRepository(db), embeddingService, vectorStore)

    private val dispatch: Set<String> = memoryTools.tools.map { it.name }.toSet()

    fun tools(): List<Tool> = memoryTools.tools

    override suspend fun execute(spec: ToolCallSpec, userId: UserId): ToolResult =
        if (spec.name in dispatch) memoryTools.execute(spec, userId)
        else error("Unhandled tool: ${spec.name}")
}
