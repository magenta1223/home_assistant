package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object IndexingOutboxTable : Table("indexing_outbox") {
    val id = integer("id").autoIncrement()
    val targetType = text("target_type")
    val targetId = integer("target_id")
    val status = text("status")
    val attempts = integer("attempts")
    val lastError = text("last_error").nullable()
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(targetType, targetId)
    }
}
