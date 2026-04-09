package com.homeassistant.domain.taxonomy

import com.homeassistant.core.tools.ToolArguments
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolName
import com.homeassistant.domain.db.tables.TaxonomyTable
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

class TaxonomyToolsTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var tools: TaxonomyTools

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        val db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TaxonomyTable) }
        tools = TaxonomyTools(TaxonomyRepository(db))
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
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
