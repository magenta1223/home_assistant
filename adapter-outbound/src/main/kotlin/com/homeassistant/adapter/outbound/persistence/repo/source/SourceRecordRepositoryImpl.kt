package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceReferenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordViewerTable
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeVisibility
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
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
        access: EpisodeAccess,
    ): SourceRecordSaveResult = transaction(db) {
        val recordsToAnalyze = mutableListOf<SourceRecord>()
        var importedRecordCount = 0
        var retriedRecordCount = 0
        var alreadyAnalyzedRecordCount = 0
        val seenDeduplicationKeys = mutableSetOf<String>()

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
                if (existingRecord.access != access) throw SourceAccessConflictException(source.name, existingRecord.access)
                if (existingRecord.analysisStatus == SourceRecordAnalysisStatus.PENDING) {
                    recordsToAnalyze += existingRecord
                    retriedRecordCount++
                } else {
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
                it[recordedAt] = record.recordedAt
                it[analysisStatus] = SourceRecordAnalysisStatus.PENDING.name
                it[visibility] = access.visibility.name
                it[SourceRecordTable.referenceId] = referenceId
            }[SourceRecordTable.id]
            access.allowedUserIds.forEach { allowedUserId ->
                SourceRecordViewerTable.insert {
                    it[sourceRecordId] = id
                    it[userId] = allowedUserId
                }
            }
            recordsToAnalyze += record.toSourceRecord(id, access)
            importedRecordCount++
        }
        SourceRecordSaveResult(
            recordsToAnalyze = recordsToAnalyze,
            importedRecordCount = importedRecordCount,
            retriedRecordCount = retriedRecordCount,
            alreadyAnalyzedRecordCount = alreadyAnalyzedRecordCount,
        )
    }

    private fun SourceRecordDraft.toSourceRecord(id: Int, access: EpisodeAccess): SourceRecord =
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

    private fun ResultRow.toAccess(): EpisodeAccess {
        val recordId = this[SourceRecordTable.id]
        val visibility = EpisodeVisibility.valueOf(this[SourceRecordTable.visibility])
        val viewers = if (visibility == EpisodeVisibility.PUBLIC) emptySet() else {
            SourceRecordViewerTable.select(SourceRecordViewerTable.userId)
                .where { SourceRecordViewerTable.sourceRecordId eq recordId }
                .mapTo(linkedSetOf()) { it[SourceRecordViewerTable.userId] }
        }
        return EpisodeAccess(visibility, viewers)
    }
}
