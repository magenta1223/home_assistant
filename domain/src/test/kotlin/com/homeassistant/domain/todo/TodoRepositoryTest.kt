package com.homeassistant.domain.todo

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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodoRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repo: TodoRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TaxonomyTable, TodoTable, SubtaskTable, TodoTaxonomyTable) }
        repo = TodoRepository(db)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
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
