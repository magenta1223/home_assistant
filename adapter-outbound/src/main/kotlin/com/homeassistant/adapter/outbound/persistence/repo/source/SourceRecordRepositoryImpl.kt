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
            var existing = SourceRecordTable.selectAll()
                .where {
                    (SourceRecordTable.sourceType eq source.type) and
                        (SourceRecordTable.deduplicationKey eq record.deduplicationKey)
                }
                .singleOrNull()
            if (existing == null && record.deduplicationAliases.isNotEmpty()) {
                val aliasMatches = SourceRecordTable.selectAll()
                    .where {
                        (SourceRecordTable.sourceType eq source.type) and
                            (SourceRecordTable.deduplicationKey inList record.deduplicationAliases)
                    }
                    .limit(2)
                    .toList()
                check(aliasMatches.size <= 1) {
                    "Multiple source records match deduplication aliases for ${record.deduplicationKey}"
                }
                existing = aliasMatches.singleOrNull()
                existing?.let { legacy ->
                    SourceRecordTable.update({ SourceRecordTable.id eq legacy[SourceRecordTable.id] }) {
                        it[deduplicationKey] = record.deduplicationKey
                        it[content] = record.content
                    }
                }
            }
            if (existing != null) {
                val existingRecord = existing.toSourceRecord().let { stored ->
                    if (stored.deduplicationKey == record.deduplicationKey) stored else stored.copy(
                        deduplicationKey = record.deduplicationKey,
                        content = record.content,
                    )
                }
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
