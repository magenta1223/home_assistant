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
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class MemoToolsTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var tools: MemoTools

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        val db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TaxonomyTable, MemoTable, MemoTaxonomyTable) }
        tools = MemoTools(MemoRepository(db))
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
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
