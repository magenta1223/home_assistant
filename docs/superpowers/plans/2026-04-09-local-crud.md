# Local CRUD Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement 18 tools across 5 domains (taxonomy, memo, todo, asset, grocery) in the `domain` module, wired into the existing tool-calling pipeline.

**Architecture:** Each domain has a `*Repository` (Exposed DSL over SQLite) and a `*Tools` class (tool schema definitions + argument parsing + ToolResult production). `DomainToolRegistry` implements `IToolExecutor` and aggregates all tools/executors. `DatabaseFactory` creates the SQLite connection and runs schema initialization.

**Tech Stack:** Kotlin + Exposed 0.57 ORM + SQLite (sqlite-jdbc 3.47) + kotlinx.serialization (all transitively available via `:core` dependency)

---

## File Map

### Created
```
domain/src/main/kotlin/com/homeassistant/domain/
  db/
    DatabaseFactory.kt
    tables/
      TaxonomyTable.kt
      MemoTable.kt
      TodoTable.kt
      AssetTable.kt
      GroceryTable.kt
  taxonomy/
    TaxonomyRepository.kt
    TaxonomyTools.kt
  memo/
    MemoRepository.kt
    MemoTools.kt
  todo/
    TodoRepository.kt
    TodoTools.kt
  asset/
    AssetRepository.kt
    AssetTools.kt
  grocery/
    GroceryRepository.kt
    GroceryTools.kt
  DomainToolRegistry.kt

domain/src/test/kotlin/com/homeassistant/domain/
  taxonomy/
    TaxonomyRepositoryTest.kt
    TaxonomyToolsTest.kt
  memo/
    MemoRepositoryTest.kt
    MemoToolsTest.kt
  todo/
    TodoRepositoryTest.kt
    TodoToolsTest.kt
  asset/
    AssetRepositoryTest.kt
    AssetToolsTest.kt
  grocery/
    GroceryRepositoryTest.kt
    GroceryToolsTest.kt
  DomainToolRegistryTest.kt
```

### Modified
```
core/src/main/kotlin/com/homeassistant/core/tools/ToolSchema.kt   — add items field to PropertySchema
app/src/main/kotlin/com/homeassistant/app/Application.kt          — wire DatabaseFactory + DomainToolRegistry
```

---

## Task 1: Table Definitions + DatabaseFactory

**Files:**
- Create: `domain/src/main/kotlin/com/homeassistant/domain/db/tables/TaxonomyTable.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/db/tables/MemoTable.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/db/tables/TodoTable.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/db/tables/AssetTable.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/db/tables/GroceryTable.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/db/DatabaseFactory.kt`

- [ ] **Step 1: Create TaxonomyTable.kt**

```kotlin
package com.homeassistant.domain.db.tables

import org.jetbrains.exposed.sql.Table

object TaxonomyTable : Table("taxonomy_nodes") {
    val id = integer("id").autoIncrement()
    val parentId = integer("parent_id").nullable()
    val name = text("name")
    val nodeType = text("node_type")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}
```

- [ ] **Step 2: Create MemoTable.kt**

```kotlin
package com.homeassistant.domain.db.tables

import org.jetbrains.exposed.sql.Table

object MemoTable : Table("memos") {
    val id = integer("id").autoIncrement()
    val title = text("title")
    val content = text("content")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object MemoTaxonomyTable : Table("memo_taxonomy") {
    val memoId = integer("memo_id").references(MemoTable.id)
    val nodeId = integer("node_id").references(TaxonomyTable.id)
    override val primaryKey = PrimaryKey(memoId, nodeId)
}
```

- [ ] **Step 3: Create TodoTable.kt**

```kotlin
package com.homeassistant.domain.db.tables

import org.jetbrains.exposed.sql.Table

object TodoTable : Table("todos") {
    val id = integer("id").autoIncrement()
    val title = text("title")
    val status = text("status")
    val createdAt = long("created_at")
    val completedAt = long("completed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object SubtaskTable : Table("subtasks") {
    val id = integer("id").autoIncrement()
    val todoId = integer("todo_id").references(TodoTable.id)
    val title = text("title")
    val status = text("status")
    val orderIndex = integer("order_index")
    override val primaryKey = PrimaryKey(id)
}

object TodoTaxonomyTable : Table("todo_taxonomy") {
    val todoId = integer("todo_id").references(TodoTable.id)
    val nodeId = integer("node_id").references(TaxonomyTable.id)
    override val primaryKey = PrimaryKey(todoId, nodeId)
}
```

- [ ] **Step 4: Create AssetTable.kt**

```kotlin
package com.homeassistant.domain.db.tables

import org.jetbrains.exposed.sql.Table

object AssetTable : Table("assets") {
    val id = integer("id").autoIncrement()
    val name = text("name")
    val assetType = text("asset_type")
    val purchasePrice = double("purchase_price").nullable()
    val currentValue = double("current_value").nullable()
    val currency = text("currency")
    val notes = text("notes").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object AssetValueHistoryTable : Table("asset_value_history") {
    val id = integer("id").autoIncrement()
    val assetId = integer("asset_id").references(AssetTable.id)
    val value = double("value")
    val recordedAt = long("recorded_at")
    override val primaryKey = PrimaryKey(id)
}
```

- [ ] **Step 5: Create GroceryTable.kt**

```kotlin
package com.homeassistant.domain.db.tables

import org.jetbrains.exposed.sql.Table

object GroceryItemTable : Table("grocery_items") {
    val id = integer("id").autoIncrement()
    val name = text("name").uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}

object GroceryPurchaseTable : Table("grocery_purchases") {
    val id = integer("id").autoIncrement()
    val groceryItemId = integer("grocery_item_id").references(GroceryItemTable.id)
    val quantity = double("quantity")
    val purchasedAt = long("purchased_at")
    override val primaryKey = PrimaryKey(id)
}
```

- [ ] **Step 6: Create DatabaseFactory.kt**

```kotlin
package com.homeassistant.domain.db

import com.homeassistant.domain.db.tables.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init(dbPath: String): Database {
        val db = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(
                TaxonomyTable,
                MemoTable, MemoTaxonomyTable,
                TodoTable, SubtaskTable, TodoTaxonomyTable,
                AssetTable, AssetValueHistoryTable,
                GroceryItemTable, GroceryPurchaseTable,
            )
        }
        return db
    }
}
```

- [ ] **Step 7: Verify compilation**

```bash
./gradlew :domain:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add domain/src/main/kotlin/com/homeassistant/domain/db/
git commit -m "feat(domain): add table definitions and DatabaseFactory"
```

---

## Task 2: TaxonomyRepository + TaxonomyTools

**Files:**
- Create: `domain/src/main/kotlin/com/homeassistant/domain/taxonomy/TaxonomyRepository.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/taxonomy/TaxonomyTools.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/taxonomy/TaxonomyRepositoryTest.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/taxonomy/TaxonomyToolsTest.kt`

- [ ] **Step 1: Write TaxonomyRepositoryTest.kt**

```kotlin
package com.homeassistant.domain.taxonomy

import com.homeassistant.domain.db.tables.TaxonomyTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaxonomyRepositoryTest {
    private lateinit var db: Database
    private lateinit var repo: TaxonomyRepository

    @BeforeTest
    fun setup() {
        db = Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TaxonomyTable) }
        repo = TaxonomyRepository(db)
    }

    @Test
    fun `create returns new id`() {
        val id = repo.create("Work", "CATEGORY", null)
        assertTrue(id > 0)
    }

    @Test
    fun `list root nodes when parentId is null`() {
        repo.create("Root", "CATEGORY", null)
        val nodes = repo.list(null)
        assertEquals(1, nodes.size)
        assertEquals("Root", nodes[0].name)
        assertNull(nodes[0].parentId)
    }

    @Test
    fun `list child nodes by parentId`() {
        val parentId = repo.create("Parent", "CATEGORY", null)
        repo.create("Child", "TAG", parentId)
        val children = repo.list(parentId)
        assertEquals(1, children.size)
        assertEquals("Child", children[0].name)
        assertEquals(parentId, children[0].parentId)
    }

    @Test
    fun `search finds nodes by name substring`() {
        repo.create("Shopping", "CATEGORY", null)
        repo.create("Work", "CATEGORY", null)
        val results = repo.search("shop")
        assertEquals(1, results.size)
        assertEquals("Shopping", results[0].name)
    }

    @Test
    fun `search is case-insensitive`() {
        repo.create("Shopping", "CATEGORY", null)
        val results = repo.search("SHOP")
        assertEquals(1, results.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.taxonomy.TaxonomyRepositoryTest"
```

Expected: FAIL — TaxonomyRepository not found

- [ ] **Step 3: Create TaxonomyRepository.kt**

```kotlin
package com.homeassistant.domain.taxonomy

import com.homeassistant.domain.db.tables.TaxonomyTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

data class TaxonomyNode(
    val id: Int,
    val parentId: Int?,
    val name: String,
    val nodeType: String,
    val createdAt: Long,
)

class TaxonomyRepository(private val db: Database) {

    fun create(name: String, nodeType: String, parentId: Int?): Int = transaction(db) {
        TaxonomyTable.insert {
            it[TaxonomyTable.name] = name
            it[TaxonomyTable.nodeType] = nodeType
            it[TaxonomyTable.parentId] = parentId
            it[createdAt] = System.currentTimeMillis()
        }[TaxonomyTable.id]
    }

    fun list(parentId: Int?): List<TaxonomyNode> = transaction(db) {
        TaxonomyTable.selectAll().where {
            if (parentId == null) TaxonomyTable.parentId.isNull()
            else TaxonomyTable.parentId eq parentId
        }.map { it.toNode() }
    }

    fun search(query: String): List<TaxonomyNode> = transaction(db) {
        TaxonomyTable.selectAll()
            .where { TaxonomyTable.name.lowerCase() like "%${query.lowercase()}%" }
            .map { it.toNode() }
    }

    private fun ResultRow.toNode() = TaxonomyNode(
        id = this[TaxonomyTable.id],
        parentId = this[TaxonomyTable.parentId],
        name = this[TaxonomyTable.name],
        nodeType = this[TaxonomyTable.nodeType],
        createdAt = this[TaxonomyTable.createdAt],
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.taxonomy.TaxonomyRepositoryTest"
```

Expected: PASS

- [ ] **Step 5: Write TaxonomyToolsTest.kt**

```kotlin
package com.homeassistant.domain.taxonomy

import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolArguments
import com.homeassistant.core.tools.ToolName
import com.homeassistant.domain.db.tables.TaxonomyTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TaxonomyToolsTest {
    private lateinit var tools: TaxonomyTools

    @BeforeTest
    fun setup() {
        val db = Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TaxonomyTable) }
        tools = TaxonomyTools(TaxonomyRepository(db))
    }

    @Test
    fun `tools list contains 3 tools`() {
        val names = tools.tools.map { it.name.value }
        assertContains(names, "taxonomy_create")
        assertContains(names, "taxonomy_list")
        assertContains(names, "taxonomy_search")
    }

    @Test
    fun `taxonomy_create returns success message with id`() {
        val result = tools.execute(ToolCallSpec(
            ToolName("taxonomy_create"),
            ToolArguments("""{"name":"Work","node_type":"CATEGORY"}""")
        ))
        assertContains(result.value, "id=")
        assertFalse(result.value.startsWith("ERROR"))
    }

    @Test
    fun `taxonomy_list returns created node`() {
        tools.execute(ToolCallSpec(ToolName("taxonomy_create"), ToolArguments("""{"name":"Work","node_type":"CATEGORY"}""")))
        val result = tools.execute(ToolCallSpec(ToolName("taxonomy_list"), ToolArguments("{}")))
        assertContains(result.value, "Work")
    }

    @Test
    fun `taxonomy_search finds node`() {
        tools.execute(ToolCallSpec(ToolName("taxonomy_create"), ToolArguments("""{"name":"Shopping","node_type":"CATEGORY"}""")))
        val result = tools.execute(ToolCallSpec(ToolName("taxonomy_search"), ToolArguments("""{"query":"shop"}""")))
        assertContains(result.value, "Shopping")
    }

    @Test
    fun `unknown tool returns error`() {
        val result = tools.execute(ToolCallSpec(ToolName("taxonomy_unknown"), ToolArguments("{}")))
        assertContains(result.value, "ERROR")
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.taxonomy.TaxonomyToolsTest"
```

Expected: FAIL — TaxonomyTools not found

- [ ] **Step 7: Create TaxonomyTools.kt**

```kotlin
package com.homeassistant.domain.taxonomy

import com.homeassistant.core.tools.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class TaxonomyTools(private val repo: TaxonomyRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class CreateArgs(val name: String, val node_type: String, val parent_id: Int? = null)
    @Serializable private data class ListArgs(val parent_id: Int? = null)
    @Serializable private data class SearchArgs(val query: String)

    val tools: List<Tool> = listOf(
        Tool(
            name = ToolName("taxonomy_create"),
            description = ToolDescription("taxonomy 노드(카테고리 또는 태그)를 생성합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "name" to PropertySchema("string", "노드 이름"),
                    "node_type" to PropertySchema("string", "노드 유형", enum = listOf("CATEGORY", "TAG")),
                    "parent_id" to PropertySchema("integer", "부모 노드 ID (루트면 생략)"),
                ),
                required = listOf("name", "node_type"),
            ),
        ),
        Tool(
            name = ToolName("taxonomy_list"),
            description = ToolDescription("taxonomy 노드 목록을 조회합니다. parent_id 없으면 루트부터"),
            schema = ToolSchema(
                properties = mapOf(
                    "parent_id" to PropertySchema("integer", "조회할 부모 노드 ID (생략 시 루트)"),
                ),
            ),
        ),
        Tool(
            name = ToolName("taxonomy_search"),
            description = ToolDescription("이름으로 taxonomy 노드를 검색합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "query" to PropertySchema("string", "검색어"),
                ),
                required = listOf("query"),
            ),
        ),
    )

    fun execute(spec: ToolCallSpec): ToolResult = try {
        when (spec.name.value) {
            "taxonomy_create" -> {
                val args = json.decodeFromString<CreateArgs>(spec.arguments.value)
                val id = repo.create(args.name, args.node_type, args.parent_id)
                ToolResult("taxonomy 노드가 생성되었습니다. id=$id name=${args.name} type=${args.node_type}")
            }
            "taxonomy_list" -> {
                val args = json.decodeFromString<ListArgs>(spec.arguments.value)
                val nodes = repo.list(args.parent_id)
                if (nodes.isEmpty()) ToolResult("조회된 taxonomy 노드가 없습니다.")
                else ToolResult(nodes.joinToString("\n") { "[${it.id}] ${it.name} (${it.nodeType})" })
            }
            "taxonomy_search" -> {
                val args = json.decodeFromString<SearchArgs>(spec.arguments.value)
                val nodes = repo.search(args.query)
                if (nodes.isEmpty()) ToolResult("'${args.query}'에 해당하는 taxonomy 노드가 없습니다.")
                else ToolResult(nodes.joinToString("\n") { "[${it.id}] ${it.name} (${it.nodeType})" })
            }
            else -> ToolResult("ERROR: 알 수 없는 tool: ${spec.name.value}")
        }
    } catch (e: Exception) {
        ToolResult("ERROR: ${e.message}")
    }
}
```

- [ ] **Step 8: Run all taxonomy tests**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.taxonomy.*"
```

Expected: PASS (5 tests)

- [ ] **Step 9: Commit**

```bash
git add domain/src/
git commit -m "feat(domain): add TaxonomyRepository and TaxonomyTools"
```

---

## Task 3: MemoRepository + MemoTools

**Files:**
- Create: `domain/src/main/kotlin/com/homeassistant/domain/memo/MemoRepository.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/memo/MemoTools.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/memo/MemoRepositoryTest.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/memo/MemoToolsTest.kt`

- [ ] **Step 1: Write MemoRepositoryTest.kt**

```kotlin
package com.homeassistant.domain.memo

import com.homeassistant.domain.db.tables.MemoTable
import com.homeassistant.domain.db.tables.MemoTaxonomyTable
import com.homeassistant.domain.db.tables.TaxonomyTable
import com.homeassistant.domain.taxonomy.TaxonomyRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoRepositoryTest {
    private lateinit var db: Database
    private lateinit var repo: MemoRepository
    private lateinit var taxRepo: TaxonomyRepository

    @BeforeTest
    fun setup() {
        db = Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TaxonomyTable, MemoTable, MemoTaxonomyTable) }
        repo = MemoRepository(db)
        taxRepo = TaxonomyRepository(db)
    }

    @Test
    fun `create and list memo without taxonomy`() {
        val id = repo.create("Title", "Content", emptyList())
        val memos = repo.list(null)
        assertEquals(1, memos.size)
        assertEquals("Title", memos[0].title)
        assertEquals(id, memos[0].id)
    }

    @Test
    fun `create memo with taxonomy node`() {
        val nodeId = taxRepo.create("Work", "CATEGORY", null)
        val memoId = repo.create("Meeting notes", "Discussed Q1 plans", listOf(nodeId))
        val memos = repo.list(nodeId)
        assertEquals(1, memos.size)
        assertEquals(memoId, memos[0].id)
        assertTrue(memos[0].nodeIds.contains(nodeId))
    }

    @Test
    fun `list with node filter excludes other memos`() {
        val nodeId = taxRepo.create("Work", "CATEGORY", null)
        repo.create("Work memo", "content", listOf(nodeId))
        repo.create("Personal memo", "content", emptyList())
        val memos = repo.list(nodeId)
        assertEquals(1, memos.size)
        assertEquals("Work memo", memos[0].title)
    }

    @Test
    fun `search finds by title`() {
        repo.create("Kotlin tips", "content", emptyList())
        repo.create("Shopping list", "content", emptyList())
        val results = repo.search("kotlin", null)
        assertEquals(1, results.size)
        assertEquals("Kotlin tips", results[0].title)
    }

    @Test
    fun `search finds by content`() {
        repo.create("Notes", "remember to buy milk", emptyList())
        val results = repo.search("milk", null)
        assertEquals(1, results.size)
    }

    @Test
    fun `update changes title and content`() {
        val id = repo.create("Old title", "Old content", emptyList())
        repo.update(id, "New title", "New content", null)
        val memos = repo.list(null)
        assertEquals("New title", memos[0].title)
        assertEquals("New content", memos[0].content)
    }

    @Test
    fun `delete removes memo`() {
        val id = repo.create("To delete", "content", emptyList())
        val deleted = repo.delete(id)
        assertTrue(deleted)
        assertTrue(repo.list(null).isEmpty())
    }

    @Test
    fun `delete returns false for nonexistent id`() {
        val deleted = repo.delete(999)
        assertTrue(!deleted)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.memo.MemoRepositoryTest"
```

Expected: FAIL — MemoRepository not found

- [ ] **Step 3: Create MemoRepository.kt**

```kotlin
package com.homeassistant.domain.memo

import com.homeassistant.domain.db.tables.MemoTable
import com.homeassistant.domain.db.tables.MemoTaxonomyTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

data class MemoRow(
    val id: Int,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val nodeIds: List<Int>,
)

class MemoRepository(private val db: Database) {

    fun create(title: String, content: String, nodeIds: List<Int>): Int = transaction(db) {
        val now = System.currentTimeMillis()
        val id = MemoTable.insert {
            it[MemoTable.title] = title
            it[MemoTable.content] = content
            it[createdAt] = now
            it[updatedAt] = now
        }[MemoTable.id]
        nodeIds.forEach { nodeId ->
            MemoTaxonomyTable.insert {
                it[memoId] = id
                it[MemoTaxonomyTable.nodeId] = nodeId
            }
        }
        id
    }

    fun list(nodeId: Int?): List<MemoRow> = transaction(db) {
        val ids = if (nodeId == null) {
            MemoTable.selectAll().map { it[MemoTable.id] }
        } else {
            MemoTaxonomyTable.selectAll()
                .where { MemoTaxonomyTable.nodeId eq nodeId }
                .map { it[MemoTaxonomyTable.memoId] }
        }
        ids.mapNotNull { fetchMemo(it) }
    }

    fun search(query: String, nodeIds: List<Int>?): List<MemoRow> = transaction(db) {
        val lower = "%${query.lowercase()}%"
        val matchingIds = MemoTable.selectAll().where {
            MemoTable.title.lowerCase() like lower or (MemoTable.content.lowerCase() like lower)
        }.map { it[MemoTable.id] }

        val filtered = if (nodeIds.isNullOrEmpty()) matchingIds else {
            MemoTaxonomyTable.selectAll()
                .where { MemoTaxonomyTable.memoId inList matchingIds and (MemoTaxonomyTable.nodeId inList nodeIds) }
                .map { it[MemoTaxonomyTable.memoId] }
                .distinct()
        }
        filtered.mapNotNull { fetchMemo(it) }
    }

    fun update(id: Int, title: String?, content: String?, nodeIds: List<Int>?) = transaction(db) {
        MemoTable.update({ MemoTable.id eq id }) {
            title?.let { t -> it[MemoTable.title] = t }
            content?.let { c -> it[MemoTable.content] = c }
            it[updatedAt] = System.currentTimeMillis()
        }
        if (nodeIds != null) {
            MemoTaxonomyTable.deleteWhere { memoId eq id }
            nodeIds.forEach { nodeId ->
                MemoTaxonomyTable.insert {
                    it[memoId] = id
                    it[MemoTaxonomyTable.nodeId] = nodeId
                }
            }
        }
    }

    fun delete(id: Int): Boolean = transaction(db) {
        MemoTaxonomyTable.deleteWhere { memoId eq id }
        MemoTable.deleteWhere { MemoTable.id eq id } > 0
    }

    private fun fetchMemo(id: Int): MemoRow? {
        val row = MemoTable.selectAll().where { MemoTable.id eq id }.singleOrNull() ?: return null
        val nodeIds = MemoTaxonomyTable.selectAll()
            .where { MemoTaxonomyTable.memoId eq id }
            .map { it[MemoTaxonomyTable.nodeId] }
        return MemoRow(
            id = row[MemoTable.id],
            title = row[MemoTable.title],
            content = row[MemoTable.content],
            createdAt = row[MemoTable.createdAt],
            updatedAt = row[MemoTable.updatedAt],
            nodeIds = nodeIds,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.memo.MemoRepositoryTest"
```

Expected: PASS

- [ ] **Step 5: Write MemoToolsTest.kt**

```kotlin
package com.homeassistant.domain.memo

import com.homeassistant.core.tools.ToolArguments
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolName
import com.homeassistant.domain.db.tables.MemoTable
import com.homeassistant.domain.db.tables.MemoTaxonomyTable
import com.homeassistant.domain.db.tables.TaxonomyTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class MemoToolsTest {
    private lateinit var tools: MemoTools

    @BeforeTest
    fun setup() {
        val db = Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TaxonomyTable, MemoTable, MemoTaxonomyTable) }
        tools = MemoTools(MemoRepository(db))
    }

    private fun spec(name: String, args: String) = ToolCallSpec(ToolName(name), ToolArguments(args))

    @Test
    fun `tools list contains 5 tools`() {
        val names = tools.tools.map { it.name.value }
        assertContains(names, "memo_create")
        assertContains(names, "memo_search")
        assertContains(names, "memo_list")
        assertContains(names, "memo_update")
        assertContains(names, "memo_delete")
    }

    @Test
    fun `memo_create returns id`() {
        val result = tools.execute(spec("memo_create", """{"title":"Test","content":"Body"}"""))
        assertContains(result.value, "id=")
        assertFalse(result.value.startsWith("ERROR"))
    }

    @Test
    fun `memo_list shows created memo`() {
        tools.execute(spec("memo_create", """{"title":"Hello","content":"World"}"""))
        val result = tools.execute(spec("memo_list", "{}"))
        assertContains(result.value, "Hello")
    }

    @Test
    fun `memo_search finds by keyword`() {
        tools.execute(spec("memo_create", """{"title":"Kotlin tips","content":"Use coroutines"}"""))
        val result = tools.execute(spec("memo_search", """{"query":"kotlin"}"""))
        assertContains(result.value, "Kotlin tips")
    }

    @Test
    fun `memo_update changes title`() {
        tools.execute(spec("memo_create", """{"title":"Old","content":"Body"}"""))
        val listResult = tools.execute(spec("memo_list", "{}"))
        val id = listResult.value.substringAfter("id=").substringBefore(" ").trim()
        tools.execute(spec("memo_update", """{"id":$id,"title":"New"}"""))
        val updated = tools.execute(spec("memo_list", "{}"))
        assertContains(updated.value, "New")
    }

    @Test
    fun `memo_delete removes memo`() {
        tools.execute(spec("memo_create", """{"title":"ToDelete","content":"Body"}"""))
        val listResult = tools.execute(spec("memo_list", "{}"))
        val id = listResult.value.substringAfter("id=").substringBefore(" ").trim()
        tools.execute(spec("memo_delete", """{"id":$id}"""))
        val afterDelete = tools.execute(spec("memo_list", "{}"))
        assertFalse(afterDelete.value.contains("ToDelete"))
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.memo.MemoToolsTest"
```

Expected: FAIL — MemoTools not found

- [ ] **Step 7: Create MemoTools.kt**

```kotlin
package com.homeassistant.domain.memo

import com.homeassistant.core.tools.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MemoTools(private val repo: MemoRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class CreateArgs(val title: String, val content: String, val node_ids: List<Int> = emptyList())
    @Serializable private data class SearchArgs(val query: String, val node_ids: List<Int>? = null)
    @Serializable private data class ListArgs(val node_id: Int? = null)
    @Serializable private data class UpdateArgs(val id: Int, val title: String? = null, val content: String? = null, val node_ids: List<Int>? = null)
    @Serializable private data class DeleteArgs(val id: Int)

    val tools: List<Tool> = listOf(
        Tool(
            name = ToolName("memo_create"),
            description = ToolDescription("메모를 생성합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "title" to PropertySchema("string", "메모 제목"),
                    "content" to PropertySchema("string", "메모 내용"),
                    "node_ids" to PropertySchema("array", "taxonomy node ID 목록", items = PropertySchema("integer", "node ID")),
                ),
                required = listOf("title", "content"),
            ),
        ),
        Tool(
            name = ToolName("memo_search"),
            description = ToolDescription("제목/내용으로 메모를 전문 검색합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "query" to PropertySchema("string", "검색어"),
                    "node_ids" to PropertySchema("array", "taxonomy 필터 (선택)", items = PropertySchema("integer", "node ID")),
                ),
                required = listOf("query"),
            ),
        ),
        Tool(
            name = ToolName("memo_list"),
            description = ToolDescription("메모 목록을 조회합니다. taxonomy 필터 가능"),
            schema = ToolSchema(
                properties = mapOf(
                    "node_id" to PropertySchema("integer", "taxonomy 필터 (선택)"),
                ),
            ),
        ),
        Tool(
            name = ToolName("memo_update"),
            description = ToolDescription("메모를 수정합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "id" to PropertySchema("integer", "메모 ID"),
                    "title" to PropertySchema("string", "새 제목 (선택)"),
                    "content" to PropertySchema("string", "새 내용 (선택)"),
                    "node_ids" to PropertySchema("array", "새 taxonomy node ID 목록 (선택)", items = PropertySchema("integer", "node ID")),
                ),
                required = listOf("id"),
            ),
        ),
        Tool(
            name = ToolName("memo_delete"),
            description = ToolDescription("메모를 삭제합니다"),
            schema = ToolSchema(
                properties = mapOf("id" to PropertySchema("integer", "메모 ID")),
                required = listOf("id"),
            ),
        ),
    )

    fun execute(spec: ToolCallSpec): ToolResult = try {
        when (spec.name.value) {
            "memo_create" -> {
                val args = json.decodeFromString<CreateArgs>(spec.arguments.value)
                val id = repo.create(args.title, args.content, args.node_ids)
                ToolResult("메모가 생성되었습니다. id=$id title=${args.title}")
            }
            "memo_search" -> {
                val args = json.decodeFromString<SearchArgs>(spec.arguments.value)
                val memos = repo.search(args.query, args.node_ids)
                if (memos.isEmpty()) ToolResult("'${args.query}'에 해당하는 메모가 없습니다.")
                else ToolResult(memos.joinToString("\n") { formatMemo(it) })
            }
            "memo_list" -> {
                val args = json.decodeFromString<ListArgs>(spec.arguments.value)
                val memos = repo.list(args.node_id)
                if (memos.isEmpty()) ToolResult("메모가 없습니다.")
                else ToolResult(memos.joinToString("\n") { formatMemo(it) })
            }
            "memo_update" -> {
                val args = json.decodeFromString<UpdateArgs>(spec.arguments.value)
                repo.update(args.id, args.title, args.content, args.node_ids)
                ToolResult("메모(id=${args.id})가 수정되었습니다.")
            }
            "memo_delete" -> {
                val args = json.decodeFromString<DeleteArgs>(spec.arguments.value)
                val deleted = repo.delete(args.id)
                if (deleted) ToolResult("메모(id=${args.id})가 삭제되었습니다.")
                else ToolResult("ERROR: id=${args.id} 메모를 찾을 수 없습니다.")
            }
            else -> ToolResult("ERROR: 알 수 없는 tool: ${spec.name.value}")
        }
    } catch (e: Exception) {
        ToolResult("ERROR: ${e.message}")
    }

    private fun formatMemo(m: MemoRow) =
        "id=${m.id} [${m.title}] ${m.content.take(80)}${if (m.content.length > 80) "..." else ""}"
}
```

- [ ] **Step 8: Add `items` field to PropertySchema in core**

Modify `core/src/main/kotlin/com/homeassistant/core/tools/ToolSchema.kt`:

```kotlin
package com.homeassistant.core.tools

import kotlinx.serialization.Serializable

@Serializable
data class ToolSchema(
    val type: String = "object",
    val properties: Map<String, PropertySchema> = emptyMap(),
    val required: List<String> = emptyList(),
)

@Serializable
data class PropertySchema(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val items: PropertySchema? = null,
)
```

- [ ] **Step 9: Run all memo tests**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.memo.*"
```

Expected: PASS (8 tests)

- [ ] **Step 10: Commit**

```bash
git add core/src/ domain/src/
git commit -m "feat(domain): add MemoRepository and MemoTools; add PropertySchema.items"
```

---

## Task 4: TodoRepository + TodoTools

**Files:**
- Create: `domain/src/main/kotlin/com/homeassistant/domain/todo/TodoRepository.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/todo/TodoTools.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/todo/TodoRepositoryTest.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/todo/TodoToolsTest.kt`

- [ ] **Step 1: Write TodoRepositoryTest.kt**

```kotlin
package com.homeassistant.domain.todo

import com.homeassistant.domain.db.tables.SubtaskTable
import com.homeassistant.domain.db.tables.TaxonomyTable
import com.homeassistant.domain.db.tables.TodoTable
import com.homeassistant.domain.db.tables.TodoTaxonomyTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodoRepositoryTest {
    private lateinit var db: Database
    private lateinit var repo: TodoRepository

    @BeforeTest
    fun setup() {
        db = Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TaxonomyTable, TodoTable, SubtaskTable, TodoTaxonomyTable) }
        repo = TodoRepository(db)
    }

    @Test
    fun `create todo and get by id`() {
        val id = repo.create("Buy groceries", emptyList(), emptyList())
        val todo = repo.get(id)
        assertNotNull(todo)
        assertEquals("Buy groceries", todo.title)
        assertEquals("PENDING", todo.status)
        assertNull(todo.completedAt)
    }

    @Test
    fun `create todo with subtasks`() {
        val id = repo.create("Cook dinner", listOf("Buy ingredients", "Chop vegetables"), emptyList())
        val todo = repo.get(id)
        assertNotNull(todo)
        assertEquals(2, todo.subtasks.size)
        assertEquals("Buy ingredients", todo.subtasks[0].title)
        assertEquals("PENDING", todo.subtasks[0].status)
    }

    @Test
    fun `complete todo sets status to DONE`() {
        val id = repo.create("Task", emptyList(), emptyList())
        val completed = repo.complete(id, null)
        assertTrue(completed)
        val todo = repo.get(id)
        assertEquals("DONE", todo?.status)
        assertNotNull(todo?.completedAt)
    }

    @Test
    fun `complete subtask sets only subtask status`() {
        val todoId = repo.create("Task", listOf("Sub"), emptyList())
        val todo = repo.get(todoId)!!
        val subtaskId = todo.subtasks[0].id
        repo.complete(todoId, subtaskId)
        val updated = repo.get(todoId)!!
        assertEquals("DONE", updated.subtasks[0].status)
        assertEquals("PENDING", updated.status)
    }

    @Test
    fun `list filters by status`() {
        repo.create("Pending task", emptyList(), emptyList())
        val doneId = repo.create("Done task", emptyList(), emptyList())
        repo.complete(doneId, null)
        val pending = repo.list("PENDING", null)
        assertEquals(1, pending.size)
        assertEquals("Pending task", pending[0].title)
    }

    @Test
    fun `add subtask appends to existing todo`() {
        val id = repo.create("Task", emptyList(), emptyList())
        repo.addSubtask(id, "New subtask")
        val todo = repo.get(id)!!
        assertEquals(1, todo.subtasks.size)
        assertEquals("New subtask", todo.subtasks[0].title)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.todo.TodoRepositoryTest"
```

Expected: FAIL — TodoRepository not found

- [ ] **Step 3: Create TodoRepository.kt**

```kotlin
package com.homeassistant.domain.todo

import com.homeassistant.domain.db.tables.SubtaskTable
import com.homeassistant.domain.db.tables.TodoTable
import com.homeassistant.domain.db.tables.TodoTaxonomyTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

data class SubtaskRow(val id: Int, val title: String, val status: String, val orderIndex: Int)

data class TodoRow(
    val id: Int,
    val title: String,
    val status: String,
    val createdAt: Long,
    val completedAt: Long?,
    val subtasks: List<SubtaskRow>,
    val nodeIds: List<Int>,
)

class TodoRepository(private val db: Database) {

    fun create(title: String, subtasks: List<String>, nodeIds: List<Int>): Int = transaction(db) {
        val now = System.currentTimeMillis()
        val id = TodoTable.insert {
            it[TodoTable.title] = title
            it[status] = "PENDING"
            it[createdAt] = now
        }[TodoTable.id]
        subtasks.forEachIndexed { index, subtaskTitle ->
            SubtaskTable.insert {
                it[todoId] = id
                it[SubtaskTable.title] = subtaskTitle
                it[status] = "PENDING"
                it[orderIndex] = index
            }
        }
        nodeIds.forEach { nodeId ->
            TodoTaxonomyTable.insert {
                it[todoId] = id
                it[TodoTaxonomyTable.nodeId] = nodeId
            }
        }
        id
    }

    fun addSubtask(todoId: Int, title: String): Int = transaction(db) {
        val nextIndex = SubtaskTable.selectAll()
            .where { SubtaskTable.todoId eq todoId }
            .count().toInt()
        SubtaskTable.insert {
            it[SubtaskTable.todoId] = todoId
            it[SubtaskTable.title] = title
            it[status] = "PENDING"
            it[orderIndex] = nextIndex
        }[SubtaskTable.id]
    }

    fun complete(todoId: Int, subtaskId: Int?): Boolean = transaction(db) {
        if (subtaskId != null) {
            SubtaskTable.update({ SubtaskTable.id eq subtaskId and (SubtaskTable.todoId eq todoId) }) {
                it[status] = "DONE"
            } > 0
        } else {
            TodoTable.update({ TodoTable.id eq todoId }) {
                it[status] = "DONE"
                it[completedAt] = System.currentTimeMillis()
            } > 0
        }
    }

    fun list(status: String?, nodeId: Int?): List<TodoRow> = transaction(db) {
        val ids = if (nodeId != null) {
            TodoTaxonomyTable.selectAll()
                .where { TodoTaxonomyTable.nodeId eq nodeId }
                .map { it[TodoTaxonomyTable.todoId] }
        } else {
            TodoTable.selectAll().map { it[TodoTable.id] }
        }
        val filtered = if (status != null) {
            TodoTable.selectAll()
                .where { TodoTable.id inList ids and (TodoTable.status eq status) }
                .map { it[TodoTable.id] }
        } else ids
        filtered.mapNotNull { fetchTodo(it) }
    }

    fun get(id: Int): TodoRow? = transaction(db) { fetchTodo(id) }

    private fun fetchTodo(id: Int): TodoRow? {
        val row = TodoTable.selectAll().where { TodoTable.id eq id }.singleOrNull() ?: return null
        val subtasks = SubtaskTable.selectAll()
            .where { SubtaskTable.todoId eq id }
            .orderBy(SubtaskTable.orderIndex)
            .map { SubtaskRow(it[SubtaskTable.id], it[SubtaskTable.title], it[SubtaskTable.status], it[SubtaskTable.orderIndex]) }
        val nodeIds = TodoTaxonomyTable.selectAll()
            .where { TodoTaxonomyTable.todoId eq id }
            .map { it[TodoTaxonomyTable.nodeId] }
        return TodoRow(
            id = row[TodoTable.id],
            title = row[TodoTable.title],
            status = row[TodoTable.status],
            createdAt = row[TodoTable.createdAt],
            completedAt = row[TodoTable.completedAt],
            subtasks = subtasks,
            nodeIds = nodeIds,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.todo.TodoRepositoryTest"
```

Expected: PASS

- [ ] **Step 5: Write TodoToolsTest.kt**

```kotlin
package com.homeassistant.domain.todo

import com.homeassistant.core.tools.ToolArguments
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolName
import com.homeassistant.domain.db.tables.SubtaskTable
import com.homeassistant.domain.db.tables.TaxonomyTable
import com.homeassistant.domain.db.tables.TodoTable
import com.homeassistant.domain.db.tables.TodoTaxonomyTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TodoToolsTest {
    private lateinit var tools: TodoTools

    @BeforeTest
    fun setup() {
        val db = Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TaxonomyTable, TodoTable, SubtaskTable, TodoTaxonomyTable) }
        tools = TodoTools(TodoRepository(db))
    }

    private fun spec(name: String, args: String) = ToolCallSpec(ToolName(name), ToolArguments(args))

    @Test
    fun `tools list contains 5 tools`() {
        val names = tools.tools.map { it.name.value }
        assertContains(names, "todo_create")
        assertContains(names, "todo_add_subtask")
        assertContains(names, "todo_complete")
        assertContains(names, "todo_list")
        assertContains(names, "todo_get")
    }

    @Test
    fun `todo_create returns id`() {
        val result = tools.execute(spec("todo_create", """{"title":"Buy groceries"}"""))
        assertContains(result.value, "id=")
        assertFalse(result.value.startsWith("ERROR"))
    }

    @Test
    fun `todo_list shows created todo`() {
        tools.execute(spec("todo_create", """{"title":"Weekly review"}"""))
        val result = tools.execute(spec("todo_list", "{}"))
        assertContains(result.value, "Weekly review")
    }

    @Test
    fun `todo_get shows subtasks`() {
        val created = tools.execute(spec("todo_create", """{"title":"Cook","subtasks":["Buy","Chop"]}"""))
        val id = created.value.substringAfter("id=").substringBefore(" ").trim()
        val result = tools.execute(spec("todo_get", """{"id":$id}"""))
        assertContains(result.value, "Buy")
        assertContains(result.value, "Chop")
    }

    @Test
    fun `todo_complete marks todo done`() {
        val created = tools.execute(spec("todo_create", """{"title":"Task"}"""))
        val id = created.value.substringAfter("id=").substringBefore(" ").trim()
        val result = tools.execute(spec("todo_complete", """{"todo_id":$id}"""))
        assertFalse(result.value.startsWith("ERROR"))
        val list = tools.execute(spec("todo_list", """{"status":"DONE"}"""))
        assertContains(list.value, "Task")
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.todo.TodoToolsTest"
```

Expected: FAIL — TodoTools not found

- [ ] **Step 7: Create TodoTools.kt**

```kotlin
package com.homeassistant.domain.todo

import com.homeassistant.core.tools.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class TodoTools(private val repo: TodoRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class CreateArgs(val title: String, val subtasks: List<String> = emptyList(), val node_ids: List<Int> = emptyList())
    @Serializable private data class AddSubtaskArgs(val todo_id: Int, val title: String)
    @Serializable private data class CompleteArgs(val todo_id: Int, val subtask_id: Int? = null)
    @Serializable private data class ListArgs(val status: String? = null, val node_id: Int? = null)
    @Serializable private data class GetArgs(val id: Int)

    val tools: List<Tool> = listOf(
        Tool(
            name = ToolName("todo_create"),
            description = ToolDescription("Todo 항목을 생성합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "title" to PropertySchema("string", "Todo 제목"),
                    "subtasks" to PropertySchema("array", "하위 작업 목록 (선택)", items = PropertySchema("string", "하위 작업 제목")),
                    "node_ids" to PropertySchema("array", "taxonomy node ID 목록 (선택)", items = PropertySchema("integer", "node ID")),
                ),
                required = listOf("title"),
            ),
        ),
        Tool(
            name = ToolName("todo_add_subtask"),
            description = ToolDescription("기존 Todo에 하위 작업을 추가합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "todo_id" to PropertySchema("integer", "Todo ID"),
                    "title" to PropertySchema("string", "하위 작업 제목"),
                ),
                required = listOf("todo_id", "title"),
            ),
        ),
        Tool(
            name = ToolName("todo_complete"),
            description = ToolDescription("Todo 또는 하위 작업을 완료 처리합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "todo_id" to PropertySchema("integer", "Todo ID"),
                    "subtask_id" to PropertySchema("integer", "하위 작업 ID (생략 시 Todo 전체 완료)"),
                ),
                required = listOf("todo_id"),
            ),
        ),
        Tool(
            name = ToolName("todo_list"),
            description = ToolDescription("Todo 목록을 조회합니다. 경과 시간 포함"),
            schema = ToolSchema(
                properties = mapOf(
                    "status" to PropertySchema("string", "상태 필터 (선택)", enum = listOf("PENDING", "DONE")),
                    "node_id" to PropertySchema("integer", "taxonomy 필터 (선택)"),
                ),
            ),
        ),
        Tool(
            name = ToolName("todo_get"),
            description = ToolDescription("Todo 상세 조회 (하위 작업 포함)"),
            schema = ToolSchema(
                properties = mapOf("id" to PropertySchema("integer", "Todo ID")),
                required = listOf("id"),
            ),
        ),
    )

    fun execute(spec: ToolCallSpec): ToolResult = try {
        when (spec.name.value) {
            "todo_create" -> {
                val args = json.decodeFromString<CreateArgs>(spec.arguments.value)
                val id = repo.create(args.title, args.subtasks, args.node_ids)
                ToolResult("Todo가 생성되었습니다. id=$id title=${args.title}")
            }
            "todo_add_subtask" -> {
                val args = json.decodeFromString<AddSubtaskArgs>(spec.arguments.value)
                val id = repo.addSubtask(args.todo_id, args.title)
                ToolResult("하위 작업이 추가되었습니다. id=$id title=${args.title}")
            }
            "todo_complete" -> {
                val args = json.decodeFromString<CompleteArgs>(spec.arguments.value)
                val completed = repo.complete(args.todo_id, args.subtask_id)
                if (completed) ToolResult("완료 처리되었습니다.")
                else ToolResult("ERROR: 해당 Todo/하위 작업을 찾을 수 없습니다.")
            }
            "todo_list" -> {
                val args = json.decodeFromString<ListArgs>(spec.arguments.value)
                val todos = repo.list(args.status, args.node_id)
                if (todos.isEmpty()) ToolResult("해당하는 Todo가 없습니다.")
                else ToolResult(todos.joinToString("\n") { formatTodo(it) })
            }
            "todo_get" -> {
                val args = json.decodeFromString<GetArgs>(spec.arguments.value)
                val todo = repo.get(args.id) ?: return ToolResult("ERROR: id=${args.id} Todo를 찾을 수 없습니다.")
                ToolResult(formatTodoDetail(todo))
            }
            else -> ToolResult("ERROR: 알 수 없는 tool: ${spec.name.value}")
        }
    } catch (e: Exception) {
        ToolResult("ERROR: ${e.message}")
    }

    private fun formatTodo(t: TodoRow): String {
        val elapsed = elapsedLabel(t.createdAt)
        val subtaskSummary = if (t.subtasks.isEmpty()) "" else " [${t.subtasks.count { it.status == "DONE" }}/${t.subtasks.size}]"
        return "id=${t.id} [${t.status}]$subtaskSummary ${t.title} ($elapsed)"
    }

    private fun formatTodoDetail(t: TodoRow): String {
        val lines = mutableListOf("id=${t.id} [${t.status}] ${t.title} (${elapsedLabel(t.createdAt)})")
        t.subtasks.forEach { lines.add("  - [${it.status}] id=${it.id} ${it.title}") }
        return lines.joinToString("\n")
    }

    private fun elapsedLabel(createdAt: Long): String {
        val diffMs = System.currentTimeMillis() - createdAt
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs) % 24
        return when {
            days > 0 -> "${days}일 전"
            hours > 0 -> "${hours}시간 전"
            else -> "방금"
        }
    }
}
```

- [ ] **Step 8: Run all todo tests**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.todo.*"
```

Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add domain/src/
git commit -m "feat(domain): add TodoRepository and TodoTools"
```

---

## Task 5: AssetRepository + AssetTools

**Files:**
- Create: `domain/src/main/kotlin/com/homeassistant/domain/asset/AssetRepository.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/asset/AssetTools.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/asset/AssetRepositoryTest.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/asset/AssetToolsTest.kt`

- [ ] **Step 1: Write AssetRepositoryTest.kt**

```kotlin
package com.homeassistant.domain.asset

import com.homeassistant.domain.db.tables.AssetTable
import com.homeassistant.domain.db.tables.AssetValueHistoryTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssetRepositoryTest {
    private lateinit var db: Database
    private lateinit var repo: AssetRepository

    @BeforeTest
    fun setup() {
        db = Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(AssetTable, AssetValueHistoryTable) }
        repo = AssetRepository(db)
    }

    @Test
    fun `add asset and list`() {
        val id = repo.add("Samsung stock", "FINANCIAL", null, 500000.0, "KRW", null)
        val assets = repo.list(null)
        assertEquals(1, assets.size)
        assertEquals("Samsung stock", assets[0].name)
        assertEquals(id, assets[0].id)
    }

    @Test
    fun `list filters by asset_type`() {
        repo.add("Apartment", "PHYSICAL", 300000000.0, 350000000.0, "KRW", null)
        repo.add("BTC", "FINANCIAL", null, 50000.0, "USD", null)
        val physical = repo.list("PHYSICAL")
        assertEquals(1, physical.size)
        assertEquals("Apartment", physical[0].name)
    }

    @Test
    fun `updateValue updates currentValue and records history`() {
        val id = repo.add("BTC", "FINANCIAL", null, 50000.0, "USD", null)
        val updated = repo.updateValue(id, 55000.0)
        assertTrue(updated)
        val assets = repo.list(null)
        assertEquals(55000.0, assets[0].currentValue)
    }

    @Test
    fun `summary groups by type and currency`() {
        repo.add("Stock A", "FINANCIAL", null, 1000.0, "KRW", null)
        repo.add("Stock B", "FINANCIAL", null, 2000.0, "KRW", null)
        repo.add("BTC", "FINANCIAL", null, 100.0, "USD", null)
        repo.add("Apartment", "PHYSICAL", null, 500000.0, "KRW", null)
        val summary = repo.summary()
        assertEquals(3000.0, summary["FINANCIAL"]?.get("KRW"))
        assertEquals(100.0, summary["FINANCIAL"]?.get("USD"))
        assertEquals(500000.0, summary["PHYSICAL"]?.get("KRW"))
    }

    @Test
    fun `updateValue returns false for nonexistent asset`() {
        val updated = repo.updateValue(999, 1000.0)
        assertTrue(!updated)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.asset.AssetRepositoryTest"
```

Expected: FAIL — AssetRepository not found

- [ ] **Step 3: Create AssetRepository.kt**

```kotlin
package com.homeassistant.domain.asset

import com.homeassistant.domain.db.tables.AssetTable
import com.homeassistant.domain.db.tables.AssetValueHistoryTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

data class AssetRow(
    val id: Int,
    val name: String,
    val assetType: String,
    val purchasePrice: Double?,
    val currentValue: Double?,
    val currency: String,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

class AssetRepository(private val db: Database) {

    fun add(name: String, assetType: String, purchasePrice: Double?, currentValue: Double?, currency: String, notes: String?): Int = transaction(db) {
        val now = System.currentTimeMillis()
        AssetTable.insert {
            it[AssetTable.name] = name
            it[AssetTable.assetType] = assetType
            it[AssetTable.purchasePrice] = purchasePrice
            it[AssetTable.currentValue] = currentValue
            it[AssetTable.currency] = currency
            it[AssetTable.notes] = notes
            it[createdAt] = now
            it[updatedAt] = now
        }[AssetTable.id]
    }

    fun updateValue(id: Int, value: Double): Boolean = transaction(db) {
        val now = System.currentTimeMillis()
        val rows = AssetTable.update({ AssetTable.id eq id }) {
            it[currentValue] = value
            it[updatedAt] = now
        }
        if (rows > 0) {
            AssetValueHistoryTable.insert {
                it[assetId] = id
                it[AssetValueHistoryTable.value] = value
                it[recordedAt] = now
            }
        }
        rows > 0
    }

    fun list(assetType: String?): List<AssetRow> = transaction(db) {
        val query = if (assetType != null)
            AssetTable.selectAll().where { AssetTable.assetType eq assetType }
        else
            AssetTable.selectAll()
        query.map { it.toRow() }
    }

    fun summary(): Map<String, Map<String, Double>> = transaction(db) {
        AssetTable.selectAll()
            .filter { it[AssetTable.currentValue] != null }
            .groupBy { it[AssetTable.assetType] }
            .mapValues { (_, rows) ->
                rows.groupBy { it[AssetTable.currency] }
                    .mapValues { (_, currencyRows) ->
                        currencyRows.sumOf { it[AssetTable.currentValue]!! }
                    }
            }
    }

    private fun ResultRow.toRow() = AssetRow(
        id = this[AssetTable.id],
        name = this[AssetTable.name],
        assetType = this[AssetTable.assetType],
        purchasePrice = this[AssetTable.purchasePrice],
        currentValue = this[AssetTable.currentValue],
        currency = this[AssetTable.currency],
        notes = this[AssetTable.notes],
        createdAt = this[AssetTable.createdAt],
        updatedAt = this[AssetTable.updatedAt],
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.asset.AssetRepositoryTest"
```

Expected: PASS

- [ ] **Step 5: Write AssetToolsTest.kt**

```kotlin
package com.homeassistant.domain.asset

import com.homeassistant.core.tools.ToolArguments
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolName
import com.homeassistant.domain.db.tables.AssetTable
import com.homeassistant.domain.db.tables.AssetValueHistoryTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AssetToolsTest {
    private lateinit var tools: AssetTools

    @BeforeTest
    fun setup() {
        val db = Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(AssetTable, AssetValueHistoryTable) }
        tools = AssetTools(AssetRepository(db))
    }

    private fun spec(name: String, args: String) = ToolCallSpec(ToolName(name), ToolArguments(args))

    @Test
    fun `tools list contains 4 tools`() {
        val names = tools.tools.map { it.name.value }
        assertContains(names, "asset_add")
        assertContains(names, "asset_update_value")
        assertContains(names, "asset_list")
        assertContains(names, "asset_summary")
    }

    @Test
    fun `asset_add returns id`() {
        val result = tools.execute(spec("asset_add", """{"name":"Samsung","asset_type":"FINANCIAL","currency":"KRW"}"""))
        assertContains(result.value, "id=")
        assertFalse(result.value.startsWith("ERROR"))
    }

    @Test
    fun `asset_list shows added asset`() {
        tools.execute(spec("asset_add", """{"name":"BTC","asset_type":"FINANCIAL","current_value":50000,"currency":"USD"}"""))
        val result = tools.execute(spec("asset_list", "{}"))
        assertContains(result.value, "BTC")
    }

    @Test
    fun `asset_update_value succeeds`() {
        val created = tools.execute(spec("asset_add", """{"name":"ETH","asset_type":"FINANCIAL","currency":"USD"}"""))
        val id = created.value.substringAfter("id=").substringBefore(" ").trim()
        val result = tools.execute(spec("asset_update_value", """{"id":$id,"value":3000}"""))
        assertFalse(result.value.startsWith("ERROR"))
    }

    @Test
    fun `asset_summary returns totals`() {
        tools.execute(spec("asset_add", """{"name":"A","asset_type":"FINANCIAL","current_value":1000,"currency":"KRW"}"""))
        tools.execute(spec("asset_add", """{"name":"B","asset_type":"FINANCIAL","current_value":2000,"currency":"KRW"}"""))
        val result = tools.execute(spec("asset_summary", "{}"))
        assertContains(result.value, "FINANCIAL")
        assertContains(result.value, "KRW")
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.asset.AssetToolsTest"
```

Expected: FAIL — AssetTools not found

- [ ] **Step 7: Create AssetTools.kt**

```kotlin
package com.homeassistant.domain.asset

import com.homeassistant.core.tools.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AssetTools(private val repo: AssetRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class AddArgs(
        val name: String, val asset_type: String,
        val purchase_price: Double? = null, val current_value: Double? = null,
        val currency: String, val notes: String? = null,
    )
    @Serializable private data class UpdateValueArgs(val id: Int, val value: Double)
    @Serializable private data class ListArgs(val asset_type: String? = null)

    val tools: List<Tool> = listOf(
        Tool(
            name = ToolName("asset_add"),
            description = ToolDescription("자산을 추가합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "name" to PropertySchema("string", "자산 이름"),
                    "asset_type" to PropertySchema("string", "자산 유형", enum = listOf("FINANCIAL", "PHYSICAL")),
                    "purchase_price" to PropertySchema("number", "매입가 (선택)"),
                    "current_value" to PropertySchema("number", "현재 가치 (선택)"),
                    "currency" to PropertySchema("string", "통화 (예: KRW, USD)"),
                    "notes" to PropertySchema("string", "메모 (선택)"),
                ),
                required = listOf("name", "asset_type", "currency"),
            ),
        ),
        Tool(
            name = ToolName("asset_update_value"),
            description = ToolDescription("자산 현재 가치를 갱신하고 이력을 기록합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "id" to PropertySchema("integer", "자산 ID"),
                    "value" to PropertySchema("number", "새 현재 가치"),
                ),
                required = listOf("id", "value"),
            ),
        ),
        Tool(
            name = ToolName("asset_list"),
            description = ToolDescription("자산 목록을 조회합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "asset_type" to PropertySchema("string", "자산 유형 필터 (선택)", enum = listOf("FINANCIAL", "PHYSICAL")),
                ),
            ),
        ),
        Tool(
            name = ToolName("asset_summary"),
            description = ToolDescription("자산 전체 합계를 유형별, 통화별로 조회합니다"),
            schema = ToolSchema(),
        ),
    )

    fun execute(spec: ToolCallSpec): ToolResult = try {
        when (spec.name.value) {
            "asset_add" -> {
                val args = json.decodeFromString<AddArgs>(spec.arguments.value)
                val id = repo.add(args.name, args.asset_type, args.purchase_price, args.current_value, args.currency, args.notes)
                ToolResult("자산이 추가되었습니다. id=$id name=${args.name}")
            }
            "asset_update_value" -> {
                val args = json.decodeFromString<UpdateValueArgs>(spec.arguments.value)
                val updated = repo.updateValue(args.id, args.value)
                if (updated) ToolResult("자산(id=${args.id}) 현재 가치가 ${args.value}로 갱신되었습니다.")
                else ToolResult("ERROR: id=${args.id} 자산을 찾을 수 없습니다.")
            }
            "asset_list" -> {
                val args = json.decodeFromString<ListArgs>(spec.arguments.value)
                val assets = repo.list(args.asset_type)
                if (assets.isEmpty()) ToolResult("자산이 없습니다.")
                else ToolResult(assets.joinToString("\n") {
                    "id=${it.id} [${it.assetType}] ${it.name} ${it.currentValue ?: "-"} ${it.currency}"
                })
            }
            "asset_summary" -> {
                val summary = repo.summary()
                if (summary.isEmpty()) return ToolResult("자산이 없습니다.")
                ToolResult(summary.entries.joinToString("\n") { (type, currencies) ->
                    currencies.entries.joinToString("\n") { (currency, total) ->
                        "$type / $currency: $total"
                    }
                })
            }
            else -> ToolResult("ERROR: 알 수 없는 tool: ${spec.name.value}")
        }
    } catch (e: Exception) {
        ToolResult("ERROR: ${e.message}")
    }
}
```

- [ ] **Step 8: Run all asset tests**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.asset.*"
```

Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add domain/src/
git commit -m "feat(domain): add AssetRepository and AssetTools"
```

---

## Task 6: GroceryRepository + GroceryTools

**Files:**
- Create: `domain/src/main/kotlin/com/homeassistant/domain/grocery/GroceryRepository.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/grocery/GroceryTools.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/grocery/GroceryRepositoryTest.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/grocery/GroceryToolsTest.kt`

- [ ] **Step 1: Write GroceryRepositoryTest.kt**

```kotlin
package com.homeassistant.domain.grocery

import com.homeassistant.domain.db.tables.GroceryItemTable
import com.homeassistant.domain.db.tables.GroceryPurchaseTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroceryRepositoryTest {
    private lateinit var db: Database
    private lateinit var repo: GroceryRepository

    @BeforeTest
    fun setup() {
        db = Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(GroceryItemTable, GroceryPurchaseTable) }
        repo = GroceryRepository(db)
    }

    @Test
    fun `recordPurchase creates item and purchase`() {
        val id = repo.recordPurchase("Milk", 2.0, null)
        assertTrue(id > 0)
        val list = repo.list()
        assertEquals(1, list.size)
        assertEquals("Milk", list[0].name)
        assertNotNull(list[0].lastPurchasedAt)
    }

    @Test
    fun `recordPurchase reuses existing item`() {
        repo.recordPurchase("Milk", 1.0, null)
        repo.recordPurchase("Milk", 2.0, null)
        val list = repo.list()
        assertEquals(1, list.size)
    }

    @Test
    fun `avgIntervalDays is null with one purchase`() {
        repo.recordPurchase("Milk", 1.0, null)
        val list = repo.list()
        assertNull(list[0].avgIntervalDays)
    }

    @Test
    fun `avgIntervalDays calculated with two purchases`() {
        val t1 = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
        val t2 = System.currentTimeMillis()
        repo.recordPurchase("Milk", 1.0, t1)
        repo.recordPurchase("Milk", 1.0, t2)
        val list = repo.list()
        val avg = list[0].avgIntervalDays
        assertNotNull(avg)
        assertTrue(avg in 9.0..11.0)
    }

    @Test
    fun `due returns items past average interval`() {
        val t1 = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(20)
        val t2 = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(12)
        repo.recordPurchase("Milk", 1.0, t1)
        repo.recordPurchase("Milk", 1.0, t2)
        // avg = 8 days, last purchased 12 days ago → due
        val due = repo.due()
        assertEquals(1, due.size)
        assertEquals("Milk", due[0].name)
    }

    @Test
    fun `due excludes items not yet past interval`() {
        val t1 = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
        val t2 = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
        repo.recordPurchase("Eggs", 1.0, t1)
        repo.recordPurchase("Eggs", 1.0, t2)
        // avg = 8 days, last purchased 2 days ago → not due
        val due = repo.due()
        assertTrue(due.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.grocery.GroceryRepositoryTest"
```

Expected: FAIL — GroceryRepository not found

- [ ] **Step 3: Create GroceryRepository.kt**

```kotlin
package com.homeassistant.domain.grocery

import com.homeassistant.domain.db.tables.GroceryItemTable
import com.homeassistant.domain.db.tables.GroceryPurchaseTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.TimeUnit

data class GroceryItemStats(
    val id: Int,
    val name: String,
    val lastPurchasedAt: Long?,
    val avgIntervalDays: Double?,
)

class GroceryRepository(private val db: Database) {

    fun recordPurchase(itemName: String, quantity: Double, purchasedAt: Long?): Int = transaction(db) {
        val existingId = GroceryItemTable.selectAll()
            .where { GroceryItemTable.name eq itemName }
            .singleOrNull()?.get(GroceryItemTable.id)

        val itemId = existingId ?: GroceryItemTable.insert {
            it[name] = itemName
        }[GroceryItemTable.id]

        GroceryPurchaseTable.insert {
            it[groceryItemId] = itemId
            it[GroceryPurchaseTable.quantity] = quantity
            it[GroceryPurchaseTable.purchasedAt] = purchasedAt ?: System.currentTimeMillis()
        }[GroceryPurchaseTable.id]
    }

    fun list(): List<GroceryItemStats> = transaction(db) {
        GroceryItemTable.selectAll().map { itemRow ->
            val itemId = itemRow[GroceryItemTable.id]
            val purchases = GroceryPurchaseTable.selectAll()
                .where { GroceryPurchaseTable.groceryItemId eq itemId }
                .orderBy(GroceryPurchaseTable.purchasedAt, SortOrder.ASC)
                .map { it[GroceryPurchaseTable.purchasedAt] }

            val lastPurchasedAt = purchases.lastOrNull()
            val avgIntervalDays = if (purchases.size >= 2) {
                val intervals = purchases.zipWithNext { a, b -> (b - a).toDouble() }
                val avgMs = intervals.average()
                avgMs / TimeUnit.DAYS.toMillis(1)
            } else null

            GroceryItemStats(itemId, itemRow[GroceryItemTable.name], lastPurchasedAt, avgIntervalDays)
        }
    }

    fun due(): List<GroceryItemStats> {
        val now = System.currentTimeMillis()
        return list().filter { stats ->
            val avg = stats.avgIntervalDays ?: return@filter false
            val last = stats.lastPurchasedAt ?: return@filter false
            val daysSinceLast = (now - last).toDouble() / TimeUnit.DAYS.toMillis(1)
            daysSinceLast >= avg
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.grocery.GroceryRepositoryTest"
```

Expected: PASS

- [ ] **Step 5: Write GroceryToolsTest.kt**

```kotlin
package com.homeassistant.domain.grocery

import com.homeassistant.core.tools.ToolArguments
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolName
import com.homeassistant.domain.db.tables.GroceryItemTable
import com.homeassistant.domain.db.tables.GroceryPurchaseTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class GroceryToolsTest {
    private lateinit var tools: GroceryTools

    @BeforeTest
    fun setup() {
        val db = Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(GroceryItemTable, GroceryPurchaseTable) }
        tools = GroceryTools(GroceryRepository(db))
    }

    private fun spec(name: String, args: String) = ToolCallSpec(ToolName(name), ToolArguments(args))

    @Test
    fun `tools list contains 3 tools`() {
        val names = tools.tools.map { it.name.value }
        assertContains(names, "grocery_record_purchase")
        assertContains(names, "grocery_list")
        assertContains(names, "grocery_due")
    }

    @Test
    fun `grocery_record_purchase succeeds`() {
        val result = tools.execute(spec("grocery_record_purchase", """{"item_name":"Milk","quantity":2}"""))
        assertFalse(result.value.startsWith("ERROR"))
    }

    @Test
    fun `grocery_list shows recorded item`() {
        tools.execute(spec("grocery_record_purchase", """{"item_name":"Eggs","quantity":12}"""))
        val result = tools.execute(spec("grocery_list", "{}"))
        assertContains(result.value, "Eggs")
    }

    @Test
    fun `grocery_due returns overdue items`() {
        val t1 = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(20)
        val t2 = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(12)
        tools.execute(spec("grocery_record_purchase", """{"item_name":"Milk","quantity":1,"purchased_at":$t1}"""))
        tools.execute(spec("grocery_record_purchase", """{"item_name":"Milk","quantity":1,"purchased_at":$t2}"""))
        val result = tools.execute(spec("grocery_due", "{}"))
        assertContains(result.value, "Milk")
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.grocery.GroceryToolsTest"
```

Expected: FAIL — GroceryTools not found

- [ ] **Step 7: Create GroceryTools.kt**

```kotlin
package com.homeassistant.domain.grocery

import com.homeassistant.core.tools.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class GroceryTools(private val repo: GroceryRepository) {

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    @Serializable private data class RecordArgs(val item_name: String, val quantity: Double, val purchased_at: Long? = null)

    val tools: List<Tool> = listOf(
        Tool(
            name = ToolName("grocery_record_purchase"),
            description = ToolDescription("식료품 구매를 기록합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "item_name" to PropertySchema("string", "식료품 이름"),
                    "quantity" to PropertySchema("number", "구매 수량"),
                    "purchased_at" to PropertySchema("integer", "구매 시각 (epoch ms, 생략 시 현재)"),
                ),
                required = listOf("item_name", "quantity"),
            ),
        ),
        Tool(
            name = ToolName("grocery_list"),
            description = ToolDescription("식료품 항목별 마지막 구매일과 평균 구매 주기를 조회합니다"),
            schema = ToolSchema(),
        ),
        Tool(
            name = ToolName("grocery_due"),
            description = ToolDescription("구매 주기가 도래한 식료품 목록을 조회합니다"),
            schema = ToolSchema(),
        ),
    )

    fun execute(spec: ToolCallSpec): ToolResult = try {
        when (spec.name.value) {
            "grocery_record_purchase" -> {
                val args = json.decodeFromString<RecordArgs>(spec.arguments.value)
                repo.recordPurchase(args.item_name, args.quantity, args.purchased_at)
                ToolResult("'${args.item_name}' ${args.quantity}개 구매가 기록되었습니다.")
            }
            "grocery_list" -> {
                val items = repo.list()
                if (items.isEmpty()) return ToolResult("기록된 식료품이 없습니다.")
                ToolResult(items.joinToString("\n") { formatItem(it) })
            }
            "grocery_due" -> {
                val items = repo.due()
                if (items.isEmpty()) return ToolResult("구매 주기가 도래한 식료품이 없습니다.")
                ToolResult("구매가 필요한 식료품:\n" + items.joinToString("\n") { formatItem(it) })
            }
            else -> ToolResult("ERROR: 알 수 없는 tool: ${spec.name.value}")
        }
    } catch (e: Exception) {
        ToolResult("ERROR: ${e.message}")
    }

    private fun formatItem(s: GroceryItemStats): String {
        val lastDate = s.lastPurchasedAt?.let { dateFmt.format(Instant.ofEpochMilli(it)) } ?: "-"
        val avg = s.avgIntervalDays?.let { "평균 %.0f일".format(it) } ?: "주기 미산출"
        return "${s.name}: 마지막 구매 $lastDate ($avg)"
    }
}
```

- [ ] **Step 8: Run all grocery tests**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.grocery.*"
```

Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add domain/src/
git commit -m "feat(domain): add GroceryRepository and GroceryTools"
```

---

## Task 7: DomainToolRegistry + Integration Test

**Files:**
- Create: `domain/src/main/kotlin/com/homeassistant/domain/DomainToolRegistry.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/DomainToolRegistryTest.kt`

- [ ] **Step 1: Write DomainToolRegistryTest.kt**

```kotlin
package com.homeassistant.domain

import com.homeassistant.core.commands.UserId
import com.homeassistant.core.tools.ToolArguments
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolName
import com.homeassistant.domain.db.tables.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DomainToolRegistryTest {
    private lateinit var registry: DomainToolRegistry

    @BeforeTest
    fun setup() {
        val db = Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(
                TaxonomyTable, MemoTable, MemoTaxonomyTable,
                TodoTable, SubtaskTable, TodoTaxonomyTable,
                AssetTable, AssetValueHistoryTable,
                GroceryItemTable, GroceryPurchaseTable,
            )
        }
        registry = DomainToolRegistry(db)
    }

    private fun spec(name: String, args: String) = ToolCallSpec(ToolName(name), ToolArguments(args))
    private val userId = UserId("test-user")

    @Test
    fun `tools() returns 18 tools`() {
        assertEquals(18, registry.tools().size)
    }

    @Test
    fun `execute taxonomy_create succeeds`() = runBlocking {
        val result = registry.execute(spec("taxonomy_create", """{"name":"Work","node_type":"CATEGORY"}"""), userId)
        assertFalse(result.value.startsWith("ERROR"))
    }

    @Test
    fun `execute memo_create and memo_list round-trip`() = runBlocking {
        registry.execute(spec("memo_create", """{"title":"Test","content":"Body"}"""), userId)
        val result = registry.execute(spec("memo_list", "{}"), userId)
        assertContains(result.value, "Test")
    }

    @Test
    fun `execute todo_create and todo_list round-trip`() = runBlocking {
        registry.execute(spec("todo_create", """{"title":"Buy milk"}"""), userId)
        val result = registry.execute(spec("todo_list", "{}"), userId)
        assertContains(result.value, "Buy milk")
    }

    @Test
    fun `execute asset_add and asset_list round-trip`() = runBlocking {
        registry.execute(spec("asset_add", """{"name":"BTC","asset_type":"FINANCIAL","currency":"USD"}"""), userId)
        val result = registry.execute(spec("asset_list", "{}"), userId)
        assertContains(result.value, "BTC")
    }

    @Test
    fun `execute grocery_record_purchase and grocery_list round-trip`() = runBlocking {
        registry.execute(spec("grocery_record_purchase", """{"item_name":"Eggs","quantity":12}"""), userId)
        val result = registry.execute(spec("grocery_list", "{}"), userId)
        assertContains(result.value, "Eggs")
    }

    @Test
    fun `execute unknown tool returns error`() = runBlocking {
        val result = registry.execute(spec("unknown_tool", "{}"), userId)
        assertContains(result.value, "ERROR")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :domain:test --tests "com.homeassistant.domain.DomainToolRegistryTest"
```

Expected: FAIL — DomainToolRegistry not found

- [ ] **Step 3: Create DomainToolRegistry.kt**

```kotlin
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
```

- [ ] **Step 4: Run all domain tests**

```bash
./gradlew :domain:test
```

Expected: PASS (all tests)

- [ ] **Step 5: Commit**

```bash
git add domain/src/
git commit -m "feat(domain): add DomainToolRegistry implementing IToolExecutor"
```

---

## Task 8: Application.kt Wiring

**Files:**
- Modify: `app/src/main/kotlin/com/homeassistant/app/Application.kt`

- [ ] **Step 1: Update Application.kt**

Replace the TODO block and wiring in `Application.kt`. The relevant section starting at line 50:

```kotlin
fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }

    install(CallLogging) {
        level = Level.INFO
    }

    val dbPath = environment.config.propertyOrNull(AppConfig.CONFIG_KEY_DB_PATH)?.getString()
        ?: AppConfig.DEFAULT_DB_PATH
    val dummy = Env[AppConfig.ENV_VAR_USE_DUMMY_PIPELINE] == "true"

    log.info("Database: $dbPath")
    log.info("Pipeline: ${if (dummy) "DUMMY" else "CHAT"}")

    val registry = DomainToolRegistry(DatabaseFactory.init(dbPath))
    val aiClient = AiClientFactory.create(NliPromptConfig(), tools = registry.tools())
    val pipeline = ChatPipeline(SessionManager(), aiClient, registry)
    configureRoutes(pipeline)
}
```

Add the two imports at the top of the file:
```kotlin
import com.homeassistant.domain.DomainToolRegistry
import com.homeassistant.domain.db.DatabaseFactory
```

Remove the import:
```kotlin
import com.homeassistant.nlp.pipeline.NoOpToolExecutor
```

- [ ] **Step 2: Verify build**

```bash
./gradlew :app:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run all tests**

```bash
./gradlew test
```

Expected: PASS

- [ ] **Step 4: Smoke test with USE_DUMMY_PIPELINE=true**

```bash
./gradlew :app:run
```

Then in another terminal:
```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"platform":"test","conversationId":"1","userId":"u1","text":"우유 2개 샀어"}' | jq .
```

Expected: JSON response without server error

- [ ] **Step 5: Commit**

```bash
git add app/src/
git commit -m "feat(app): wire DomainToolRegistry and DatabaseFactory into Application"
```

---

## Spec Coverage Check

| Spec requirement | Task |
|---|---|
| DatabaseFactory + 5 tables | Task 1 |
| TaxonomyRepository + 3 tools (create/list/search) | Task 2 |
| MemoRepository + 5 tools (create/search/list/update/delete) | Task 3 |
| TodoRepository + 5 tools (create/add_subtask/complete/list/get) | Task 4 |
| AssetRepository + 4 tools (add/update_value/list/summary) | Task 5 |
| GroceryRepository + 3 tools (record/list/due) | Task 6 |
| DomainToolRegistry aggregates all 18 tools | Task 7 |
| Application.kt wiring | Task 8 |
| PropertySchema.items for array schemas | Task 3, Step 8 |
| Error handling via ToolResult("ERROR: ...") | All *Tools tasks |
| `:memory:` SQLite unit tests | Tasks 2–7 |
| Tool executor integration tests | Task 7 |
