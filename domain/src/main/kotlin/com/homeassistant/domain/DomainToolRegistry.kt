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

    private val taxonomyTools = TaxonomyTools(TaxonomyRepository(db))
    private val memoTools = MemoTools(MemoRepository(db))
    private val todoTools = TodoTools(TodoRepository(db))
    private val assetTools = AssetTools(AssetRepository(db))
    private val groceryTools = GroceryTools(GroceryRepository(db))

    fun tools(): List<Tool> =
        taxonomyTools.tools + memoTools.tools + todoTools.tools + assetTools.tools + groceryTools.tools

    override suspend fun execute(spec: ToolCallSpec, userId: UserId): ToolResult = when {
        spec.name.value.startsWith("taxonomy_") -> taxonomyTools.execute(spec)
        spec.name.value.startsWith("memo_") -> memoTools.execute(spec)
        spec.name.value.startsWith("todo_") -> todoTools.execute(spec)
        spec.name.value.startsWith("asset_") -> assetTools.execute(spec)
        spec.name.value.startsWith("grocery_") -> groceryTools.execute(spec)
        else -> ToolResult("ERROR: 알 수 없는 tool: ${spec.name.value}")
    }
}
