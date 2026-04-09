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
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TodoToolsTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var tools: TodoTools

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        val db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TaxonomyTable, TodoTable, SubtaskTable, TodoTaxonomyTable) }
        tools = TodoTools(TodoRepository(db))
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
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
