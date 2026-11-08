package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceReferenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordViewerTable
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.access.Visibility
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.domain.source.SourceReference
import com.homeassistant.domain.source.SourceReferenceDraft
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction

internal class SourceRecordRepositoryImpl(private val db: Database) : SourceRecordRepository {

    override fun save(
        source: SourceDescriptor,
        records: List<SourceRecordDraft>,
        access: AccessControl,
    ): SourceRecordSaveResult = transaction(db) {
        val drafts = records.distinctBy { it.deduplicationKey }
        val stored = drafts.associate { it.deduplicationKey to findStored(source, it.deduplicationKey) }
        stored.values.filterNotNull().firstOrNull { it.access != access }
            ?.let { return@transaction SourceRecordSaveResult.AccessConflict(it.access) }

        val recordsToAnalyze = mutableListOf<SourceRecord>()
        var importedRecordCount = 0
        var retriedRecordCount = 0
        var alreadyAnalyzedRecordCount = 0
        drafts.forEach { draft ->
            val existing = stored.getValue(draft.deduplicationKey)
            when {
                existing == null -> {
                    recordsToAnalyze += insert(source, draft, access)
                    importedRecordCount++
                }
                existing.analysisStatus == SourceRecordAnalysisStatus.PENDING -> {
                    recordsToAnalyze += existing
                    retriedRecordCount++
                }
                else -> alreadyAnalyzedRecordCount++
            }
        }
        SourceRecordSaveResult.Saved(
            recordsToAnalyze = recordsToAnalyze,
            importedRecordCount = importedRecordCount,
            retriedRecordCount = retriedRecordCount,
            alreadyAnalyzedRecordCount = alreadyAnalyzedRecordCount,
        )
    }

    /** The record of [source]'s type stored under [deduplicationKey], or null when there is none. */
    private fun findStored(source: SourceDescriptor, deduplicationKey: String): SourceRecord? =
        SourceRecordTable.selectAll()
            .where {
                (SourceRecordTable.sourceType eq source.type) and
                    (SourceRecordTable.deduplicationKey eq deduplicationKey)
            }
            .singleOrNull()
            ?.toSourceRecord()

    /** Stores [draft] as a pending record of [source] readable as [access]. */
    private fun insert(source: SourceDescriptor, draft: SourceRecordDraft, access: AccessControl): SourceRecord {
        val referenceId = draft.reference?.let(::saveReference)
        val id = SourceRecordTable.insert {
            it[sourceType] = source.type
            it[sourceName] = source.name
            it[content] = draft.content
            it[deduplicationKey] = draft.deduplicationKey
            it[recordedAt] = draft.recordedAt
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
        return draft.toSourceRecord(id, access)
    }

    private fun SourceRecordDraft.toSourceRecord(id: Int, access: AccessControl): SourceRecord =
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

    private fun ResultRow.toAccess(): AccessControl {
        val recordId = this[SourceRecordTable.id]
        val visibility = Visibility.valueOf(this[SourceRecordTable.visibility])
        val viewers = if (visibility == Visibility.PUBLIC) emptySet() else {
            SourceRecordViewerTable.select(SourceRecordViewerTable.userId)
                .where { SourceRecordViewerTable.sourceRecordId eq recordId }
                .mapTo(linkedSetOf()) { it[SourceRecordViewerTable.userId] }
        }
        return AccessControl(visibility, viewers)
    }
}
