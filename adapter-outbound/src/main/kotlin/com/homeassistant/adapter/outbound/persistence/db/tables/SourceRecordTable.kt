package com.homeassistant.adapter.outbound.persistence.db.tables

import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import com.homeassistant.domain.memory.MemoryVisibility
import org.jetbrains.exposed.sql.Table

internal object SourceRecordTable : Table("source_records") {
    val id = integer("id").autoIncrement()
    val sourceType = text("source_type")
    val sourceName = text("source_name")
    val content = text("content")
    val deduplicationKey = text("deduplication_key")
    val createdAt = long("created_at")
    val analysisStatus = text("analysis_status").default(SourceRecordAnalysisStatus.ANALYZED.name)
    val visibility = text("visibility").default(MemoryVisibility.PUBLIC.name)
    /** False only for rows created before explicit source audiences existed. */
    val audienceExplicit = bool("audience_explicit").default(false)
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(sourceType, deduplicationKey)
        index(false, sourceType, sourceName)
    }
}

internal object SourceRecordViewerTable : Table("source_record_viewers") {
    val sourceRecordId = integer("source_record_id").references(SourceRecordTable.id)
    val userId = text("user_id")
    override val primaryKey = PrimaryKey(sourceRecordId, userId)

    init {
        index(false, userId, sourceRecordId)
    }
}
