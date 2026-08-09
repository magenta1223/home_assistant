package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordViewerTable
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.domain.source.SourceRecordSaveResult
import com.homeassistant.domain.source.SourceAccessConflictException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

internal class SourceRecordRepositoryImpl(private val db: Database) : SourceRecordRepository {

    override fun saveAll(
        source: SourceDescriptor,
        records: List<SourceRecordDraft>,
        access: MemoryAccess,
    ): SourceRecordSaveResult = transaction(db) {
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
                val existingAccess = if (existing[SourceRecordTable.audienceExplicit]) {
                    existing.toAccess().also { storedAccess ->
                        if (storedAccess != access) throw SourceAccessConflictException(source.name)
                    }
                } else {
                    assignAccess(existing[SourceRecordTable.id], access)
                    access
                }
                val existingRecord = existing.toSourceRecord().copy(access = existingAccess).let { stored ->
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
                it[visibility] = access.visibility.name
                it[audienceExplicit] = true
            }[SourceRecordTable.id]
            access.allowedUserIds.forEach { allowedUserId ->
                SourceRecordViewerTable.insert {
                    it[sourceRecordId] = id
                    it[userId] = allowedUserId
                }
            }
            analysisStarted = true
            recordsToAnalyze += record.toSourceRecord(id, access)
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

    private fun SourceRecordDraft.toSourceRecord(id: Int, access: MemoryAccess): SourceRecord =
        SourceRecord(
            id = id,
            deduplicationKey = deduplicationKey,
            content = content,
            analysisStatus = SourceRecordAnalysisStatus.PENDING,
            access = access,
        )

    private fun ResultRow.toSourceRecord(): SourceRecord =
        SourceRecord(
            id = this[SourceRecordTable.id],
            deduplicationKey = this[SourceRecordTable.deduplicationKey],
            content = this[SourceRecordTable.content],
            analysisStatus = SourceRecordAnalysisStatus.valueOf(this[SourceRecordTable.analysisStatus]),
            access = toAccess(),
        )

    private fun ResultRow.toAccess(): MemoryAccess {
        val recordId = this[SourceRecordTable.id]
        val visibility = MemoryVisibility.valueOf(this[SourceRecordTable.visibility])
        val viewers = if (visibility == MemoryVisibility.PUBLIC) emptySet() else {
            SourceRecordViewerTable.select(SourceRecordViewerTable.userId)
                .where { SourceRecordViewerTable.sourceRecordId eq recordId }
                .mapTo(linkedSetOf()) { it[SourceRecordViewerTable.userId] }
        }
        return MemoryAccess(visibility, viewers)
    }

    private fun assignAccess(recordId: Int, access: MemoryAccess) {
        SourceRecordViewerTable.deleteWhere {
            SourceRecordViewerTable.sourceRecordId eq recordId
        }
        SourceRecordTable.update({ SourceRecordTable.id eq recordId }) {
            it[visibility] = access.visibility.name
            it[audienceExplicit] = true
        }
        access.allowedUserIds.forEach { allowedUserId ->
            SourceRecordViewerTable.insert {
                it[SourceRecordViewerTable.sourceRecordId] = recordId
                it[userId] = allowedUserId
            }
        }
    }

    private companion object {
        const val CONTEXT_RECORD_LIMIT = 20
    }
}
