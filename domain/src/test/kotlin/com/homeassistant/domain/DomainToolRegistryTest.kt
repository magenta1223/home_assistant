package com.homeassistant.domain

import com.homeassistant.core.commands.UserId
import com.homeassistant.core.tools.ToolArguments
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolName
import com.homeassistant.domain.db.tables.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DomainToolRegistryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var registry: DomainToolRegistry

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        val db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
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

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    private fun spec(name: String, args: String) = ToolCallSpec(ToolName(name), ToolArguments(args))
    private val userId = UserId("test-user")

    @Test
    fun `tools() returns 18 tools`() {
        assertEquals(20, registry.tools().size)
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
