package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.domain.source.SourceRecordSaveResult
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class SourceRecordRepositoryImpl(private val db: Database) : SourceRecordRepository {

    override fun saveAll(source: SourceDescriptor, records: List<SourceRecordDraft>): SourceRecordSaveResult = transaction(db) {
        val recordsToAnalyze = mutableListOf<SourceRecord>()
        var importedRecordCount = 0
        var retriedRecordCount = 0
        var alreadyAnalyzedRecordCount = 0
        val seenDeduplicationKeys = mutableSetOf<String>()
        val contextRecords = ArrayDeque<SourceRecord>()
        var analysisStarted = false

        records.forEach { record ->
            if (!seenDeduplicationKeys.add(record.deduplicationKey)) {
                return@forEach
            }
            val existing = SourceRecordTable.selectAll()
                .where {
                    (SourceRecordTable.sourceType eq source.type) and
                        (SourceRecordTable.deduplicationKey eq record.deduplicationKey)
                }
                .singleOrNull()
            if (existing != null) {
                val existingRecord = existing.toSourceRecord()
                if (existingRecord.analysisStatus == SourceRecordAnalysisStatus.PENDING) {
                    analysisStarted = true
                    recordsToAnalyze += existingRecord
                    retriedRecordCount++
                } else {
                    if (!analysisStarted) {
                        contextRecords += existingRecord
                        if (contextRecords.size > CONTEXT_RECORD_LIMIT) contextRecords.removeFirst()
                    }
                    alreadyAnalyzedRecordCount++
                }
                return@forEach
            }

            val id = SourceRecordTable.insert {
                it[sourceType] = source.type
                it[sourceName] = source.name
                it[content] = record.content
                it[deduplicationKey] = record.deduplicationKey
                it[createdAt] = System.currentTimeMillis()
                it[analysisStatus] = SourceRecordAnalysisStatus.PENDING.name
            }[SourceRecordTable.id]
            analysisStarted = true
            recordsToAnalyze += record.toSourceRecord(id)
            importedRecordCount++
        }
        SourceRecordSaveResult(
            recordsToAnalyze = recordsToAnalyze,
            contextRecords = contextRecords.toList(),
            importedRecordCount = importedRecordCount,
            retriedRecordCount = retriedRecordCount,
            alreadyAnalyzedRecordCount = alreadyAnalyzedRecordCount,
        )
    }

    override fun markAnalyzed(recordIds: Collection<Int>): Unit = transaction(db) {
        if (recordIds.isEmpty()) return@transaction
        SourceRecordTable.update({ SourceRecordTable.id inList recordIds }) {
            it[analysisStatus] = SourceRecordAnalysisStatus.ANALYZED.name
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

    override fun findRecentAnalyzed(source: SourceDescriptor, limit: Int): List<SourceRecord> = transaction(db) {
        require(limit >= 0) { "limit must not be negative" }
        if (limit == 0) return@transaction emptyList()
        SourceRecordTable.selectAll()
            .where {
                (SourceRecordTable.sourceType eq source.type) and
                    (SourceRecordTable.sourceName eq source.name) and
                    (SourceRecordTable.analysisStatus eq SourceRecordAnalysisStatus.ANALYZED.name)
            }
            .orderBy(SourceRecordTable.id, SortOrder.DESC)
            .limit(limit)
            .map { it.toSourceRecord() }
            .asReversed()
    }

    private fun SourceRecordDraft.toSourceRecord(id: Int): SourceRecord =
        SourceRecord(
            id = id,
            deduplicationKey = deduplicationKey,
            content = content,
            analysisStatus = SourceRecordAnalysisStatus.PENDING,
        )

    private fun ResultRow.toSourceRecord(): SourceRecord =
        SourceRecord(
            id = this[SourceRecordTable.id],
            deduplicationKey = this[SourceRecordTable.deduplicationKey],
            content = this[SourceRecordTable.content],
            analysisStatus = SourceRecordAnalysisStatus.valueOf(this[SourceRecordTable.analysisStatus]),
        )

    private companion object {
        const val CONTEXT_RECORD_LIMIT = 20
    }
}
