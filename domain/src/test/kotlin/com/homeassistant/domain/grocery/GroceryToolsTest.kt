package com.homeassistant.domain.grocery

import com.homeassistant.core.tools.ToolArguments
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolName
import com.homeassistant.domain.db.tables.GroceryItemTable
import com.homeassistant.domain.db.tables.GroceryPurchaseTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class GroceryToolsTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var tools: GroceryTools

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        val db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(GroceryItemTable, GroceryPurchaseTable) }
        tools = GroceryTools(GroceryRepository(db))
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
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
