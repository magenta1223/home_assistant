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
