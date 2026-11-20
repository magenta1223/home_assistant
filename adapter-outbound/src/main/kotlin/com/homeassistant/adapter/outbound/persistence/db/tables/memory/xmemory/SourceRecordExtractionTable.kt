package com.homeassistant.adapter.outbound.persistence.db.tables.memory.xmemory

import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import org.jetbrains.exposed.sql.Table

/** How far xMemory's Episode extraction of each stored source record has come; a record without a row was never claimed. */
internal object SourceRecordExtractionTable : Table("xmemory_source_record_extractions") {
    val sourceRecordId = integer("source_record_id").references(SourceRecordTable.id)
    val status = text("status")
    val attempts = integer("attempts")
    val claimId = text("claim_id").nullable()
    val lastError = text("last_error").nullable()
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(sourceRecordId)

    init {
        index(false, claimId)
    }

    const val PROCESSING = "PROCESSING"
    const val FAILED = "FAILED"
    const val EXTRACTED = "EXTRACTED"
}
