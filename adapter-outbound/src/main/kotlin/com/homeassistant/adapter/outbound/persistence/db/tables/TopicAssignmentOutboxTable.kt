package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object TopicAssignmentOutboxTable : Table("topic_assignment_outbox") {
    val id = integer("id").autoIncrement()
    val memoryId = integer("memory_id").references(MemoryTable.id).uniqueIndex()
    val status = text("status")
    val attempts = integer("attempts")
    val lastError = text("last_error").nullable()
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)

    const val PENDING = "PENDING"
    const val PROCESSING = "PROCESSING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
}
