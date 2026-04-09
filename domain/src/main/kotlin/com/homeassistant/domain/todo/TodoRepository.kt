package com.homeassistant.domain.todo

import com.homeassistant.domain.db.tables.SubtaskTable
import com.homeassistant.domain.db.tables.TodoTable
import com.homeassistant.domain.db.tables.TodoTaxonomyTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
            SubtaskTable.update({
                Op.build { SubtaskTable.id eq subtaskId and (SubtaskTable.todoId eq todoId) }
            }) {
                it[status] = "DONE"
            } > 0
        } else {
            TodoTable.update({ Op.build { TodoTable.id eq todoId } }) {
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
