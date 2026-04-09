package com.homeassistant.domain.asset

import com.homeassistant.core.tools.ToolArguments
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolName
import com.homeassistant.domain.db.tables.AssetTable
import com.homeassistant.domain.db.tables.AssetValueHistoryTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AssetToolsTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var tools: AssetTools

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        val db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(AssetTable, AssetValueHistoryTable) }
        tools = AssetTools(AssetRepository(db))
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
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
