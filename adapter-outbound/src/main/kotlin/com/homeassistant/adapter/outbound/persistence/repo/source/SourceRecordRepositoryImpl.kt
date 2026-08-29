package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceReferenceTable
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
import com.homeassistant.domain.source.SourceReference
import com.homeassistant.domain.source.SourceReferenceDraft
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
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
                    val referenceId = record.reference?.let(::saveReference)
                    SourceRecordTable.update({ SourceRecordTable.id eq legacy[SourceRecordTable.id] }) {
                        it[deduplicationKey] = record.deduplicationKey
                        it[content] = record.content
                        if (referenceId != null) it[SourceRecordTable.referenceId] = referenceId
                    }
                    existing = SourceRecordTable.selectAll()
                        .where { SourceRecordTable.id eq legacy[SourceRecordTable.id] }
                        .single()
                }
            }
            if (existing != null) {
                val existingAccess = if (existing[SourceRecordTable.audienceExplicit]) {
                    existing.toAccess().also { storedAccess ->
                        if (storedAccess != access) throw SourceAccessConflictException(source.name, storedAccess)
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

            val referenceId = record.reference?.let(::saveReference)
            val id = SourceRecordTable.insert {
                it[sourceType] = source.type
                it[sourceName] = source.name
                it[content] = record.content
                it[deduplicationKey] = record.deduplicationKey
                it[createdAt] = System.currentTimeMillis()
                it[analysisStatus] = SourceRecordAnalysisStatus.PENDING.name
                it[visibility] = access.visibility.name
                it[audienceExplicit] = true
                it[SourceRecordTable.referenceId] = referenceId
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
            reference = reference?.let { draft ->
                val stored = SourceReferenceTable.selectAll()
                    .where { SourceReferenceTable.sha256 eq draft.sha256 }
                    .single()
                stored.toSourceReference()
            },
        )

    private fun ResultRow.toSourceRecord(): SourceRecord =
        SourceRecord(
            id = this[SourceRecordTable.id],
            deduplicationKey = this[SourceRecordTable.deduplicationKey],
            content = this[SourceRecordTable.content],
            analysisStatus = SourceRecordAnalysisStatus.valueOf(this[SourceRecordTable.analysisStatus]),
            access = toAccess(),
            reference = this[SourceRecordTable.referenceId]?.let { referenceId ->
                SourceReferenceTable.selectAll()
                    .where { SourceReferenceTable.id eq referenceId }
                    .single()
                    .toSourceReference()
            },
        )

    private fun saveReference(reference: SourceReferenceDraft): Int {
        val existing = SourceReferenceTable.selectAll()
            .where { SourceReferenceTable.sha256 eq reference.sha256 }
            .singleOrNull()
        if (existing != null) return existing[SourceReferenceTable.id]
        return SourceReferenceTable.insert {
            it[fileName] = reference.fileName
            it[mediaType] = reference.mediaType
            it[size] = reference.size
            it[sha256] = reference.sha256
            it[content] = ExposedBlob(reference.bytes())
            it[createdAt] = System.currentTimeMillis()
        }[SourceReferenceTable.id]
    }

    private fun ResultRow.toSourceReference(): SourceReference = SourceReference(
        id = this[SourceReferenceTable.id],
        fileName = this[SourceReferenceTable.fileName],
        mediaType = this[SourceReferenceTable.mediaType],
        size = this[SourceReferenceTable.size],
        sha256 = this[SourceReferenceTable.sha256],
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
