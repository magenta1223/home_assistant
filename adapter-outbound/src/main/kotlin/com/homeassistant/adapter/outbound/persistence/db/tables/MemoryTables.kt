package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object MemoryTable : Table("memories") {
    val id = integer("id").autoIncrement()
    val parentId = integer("parent_id").references(id).nullable()
    val createdByUserId = text("created_by_user_id")
    val content = text("content")
    val subject = text("subject")
    val memoryType = text("memory_type")
    val certainty = text("certainty")
    val visibility = text("visibility")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, parentId)
        index(false, createdByUserId, visibility)
        index(false, memoryType)
    }
}

internal object MemoryEvidenceTable : Table("memory_evidence") {
    val memoryId = integer("memory_id").references(MemoryTable.id)
    val sourceRecordId = integer("source_record_id").references(SourceRecordTable.id)
    override val primaryKey = PrimaryKey(memoryId, sourceRecordId)

    init {
        index(false, sourceRecordId)
    }
}
