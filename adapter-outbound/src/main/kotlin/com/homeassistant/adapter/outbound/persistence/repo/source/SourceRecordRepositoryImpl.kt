package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class SourceRecordRepositoryImpl(private val db: Database) : SourceRecordRepository {

    override fun saveAll(source: SourceDescriptor, records: List<SourceRecordDraft>): List<SourceRecord> = transaction(db) {
        records.mapNotNull { record ->
            val existing = SourceRecordTable.selectAll()
                .where {
                    (SourceRecordTable.sourceType eq source.type) and
                        (SourceRecordTable.deduplicationKey eq record.deduplicationKey)
                }
                .singleOrNull()
            if (existing != null) return@mapNotNull null

            val id = SourceRecordTable.insert {
                it[sourceType] = source.type
                it[sourceName] = source.name
                it[content] = record.content
                it[deduplicationKey] = record.deduplicationKey
                it[createdAt] = System.currentTimeMillis()
            }[SourceRecordTable.id]
            record.toSourceRecord(id)
        }
    }

    override fun findBySource(source: SourceDescriptor): List<SourceRecord> = transaction(db) {
        SourceRecordTable.selectAll()
            .where {
                (SourceRecordTable.sourceType eq source.type) and
                    (SourceRecordTable.sourceName eq source.name)
            }
            .orderBy(SourceRecordTable.id)
            .map { it.toSourceRecord() }
    }

    private fun SourceRecordDraft.toSourceRecord(id: Int): SourceRecord =
        SourceRecord(
            id = id,
            deduplicationKey = deduplicationKey,
            content = content,
        )

    private fun ResultRow.toSourceRecord(): SourceRecord =
        SourceRecord(
            id = this[SourceRecordTable.id],
            deduplicationKey = this[SourceRecordTable.deduplicationKey],
            content = this[SourceRecordTable.content],
        )
}