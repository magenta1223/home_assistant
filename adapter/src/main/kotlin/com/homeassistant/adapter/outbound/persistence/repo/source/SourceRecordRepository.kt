package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceRecordStore
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class SourceRecordRepository(private val db: Database) : SourceRecordStore {
    override fun findExistingDeduplicationKeys(sourceType: String, keys: Set<String>): Set<String> = transaction(db) {
        if (keys.isEmpty()) return@transaction emptySet()

        keys
            .chunked(DEDUPLICATION_KEY_QUERY_BATCH_SIZE)
            .flatMapTo(mutableSetOf()) { batch ->
                SourceRecordTable
                    .select(SourceRecordTable.deduplicationKey)
                    .where {
                        (SourceRecordTable.sourceType eq sourceType) and
                            (SourceRecordTable.deduplicationKey inList batch)
                    }
                    .map { it[SourceRecordTable.deduplicationKey] }
            }
    }

    override fun saveAll(records: List<SourceRecordDraft>): List<SourceRecord> = transaction(db) {
        records.map { record ->
            val existing = SourceRecordTable.selectAll()
                .where {
                    (SourceRecordTable.sourceType eq record.sourceType) and
                        (SourceRecordTable.deduplicationKey eq record.deduplicationKey)
                }
                .singleOrNull()
            if (existing != null) return@map existing.toSourceRecord()

            val id = SourceRecordTable.insert {
                it[sourceType] = record.sourceType
                it[sourceName] = record.sourceName
                it[content] = record.content
                it[deduplicationKey] = record.deduplicationKey
                it[createdAt] = System.currentTimeMillis()
            }[SourceRecordTable.id]
            record.toSourceRecord(id)
        }
    }

    override fun findBySource(sourceType: String, sourceName: String): List<SourceRecord> = transaction(db) {
        SourceRecordTable.selectAll()
            .where {
                (SourceRecordTable.sourceType eq sourceType) and
                    (SourceRecordTable.sourceName eq sourceName)
            }
            .orderBy(SourceRecordTable.id)
            .map { it.toSourceRecord() }
    }

    private fun SourceRecordDraft.toSourceRecord(id: Int): SourceRecord =
        SourceRecord(
            id = id,
            sourceType = sourceType,
            sourceName = sourceName,
            deduplicationKey = deduplicationKey,
            content = content,
        )

    private fun ResultRow.toSourceRecord(): SourceRecord =
        SourceRecord(
            id = this[SourceRecordTable.id],
            sourceType = this[SourceRecordTable.sourceType],
            sourceName = this[SourceRecordTable.sourceName],
            deduplicationKey = this[SourceRecordTable.deduplicationKey],
            content = this[SourceRecordTable.content],
        )
}

private const val DEDUPLICATION_KEY_QUERY_BATCH_SIZE = 500
