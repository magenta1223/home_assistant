package com.homeassistant.domain

import com.homeassistant.core.commands.UserId
import com.homeassistant.core.tools.*
import com.homeassistant.domain.asset.AssetRepository
import com.homeassistant.domain.asset.AssetTools
import com.homeassistant.domain.grocery.GroceryRepository
import com.homeassistant.domain.grocery.GroceryTools
import com.homeassistant.domain.memo.MemoRepository
import com.homeassistant.domain.memo.MemoTools
import com.homeassistant.domain.taxonomy.TaxonomyRepository
import com.homeassistant.domain.taxonomy.TaxonomyTools
import com.homeassistant.domain.todo.TodoRepository
import com.homeassistant.domain.todo.TodoTools
import org.jetbrains.exposed.sql.Database

class DomainToolRegistry(db: Database) : IToolExecutor {

    private val groups: List<ToolGroup> = listOf(
        TaxonomyTools(TaxonomyRepository(db)),
        MemoTools(MemoRepository(db)),
        TodoTools(TodoRepository(db)),
        AssetTools(AssetRepository(db)),
        GroceryTools(GroceryRepository(db)),
    )

    private val dispatch: Map<ToolName, ToolGroup> =
        groups.flatMap { g -> g.tools.map { it.name to g } }.toMap()

    fun tools(): List<Tool> = groups.flatMap { it.tools }

    override suspend fun execute(spec: ToolCallSpec, userId: UserId): ToolResult =
        dispatch[spec.name]?.execute(spec) ?: error("Unhandled tool: ${spec.name.value}")
}
