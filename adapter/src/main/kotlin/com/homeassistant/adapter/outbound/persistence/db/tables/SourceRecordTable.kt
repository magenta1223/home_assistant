package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object SourceRecordTable : Table("source_records") {
    val id = integer("id").autoIncrement()
    val sourceType = text("source_type")
    val sourceName = text("source_name")
    val content = text("content")
    val deduplicationKey = text("deduplication_key")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(sourceType, deduplicationKey)
        index(false, sourceType, sourceName)
    }
}
