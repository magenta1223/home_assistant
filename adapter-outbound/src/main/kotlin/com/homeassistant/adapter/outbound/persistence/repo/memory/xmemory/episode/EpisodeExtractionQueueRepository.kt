package com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.episode

import com.homeassistant.adapter.outbound.persistence.db.SqliteDatabases
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.db.tables.memory.xmemory.SourceRecordExtractionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.memory.xmemory.SourceRecordExtractionTable.EXTRACTED
import com.homeassistant.adapter.outbound.persistence.db.tables.memory.xmemory.SourceRecordExtractionTable.FAILED
import com.homeassistant.adapter.outbound.persistence.db.tables.memory.xmemory.SourceRecordExtractionTable.PROCESSING
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeExtractionClaim
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeExtractionQueue
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import java.util.UUID

internal class EpisodeExtractionQueueRepository(private val db: SqliteDatabases) : EpisodeExtractionQueue {
    override fun claimNext(
        now: Long,
        retryBefore: Long,
        staleProcessingBefore: Long,
        maxAttempts: Int,
    ): EpisodeExtractionClaim? = db.write {
        require(maxAttempts > 0)
        val ready = readyCondition(retryBefore, staleProcessingBefore, maxAttempts)
        val first = readyRecords(ready).limit(1).singleOrNull() ?: return@write null
        val rows = readyRecords(ready and sameSourceAndSubmitter(first)).toList()
        val claimId = UUID.randomUUID().toString()
        claim(rows.map { it[SourceRecordTable.id] }, claimId, now)
        EpisodeExtractionClaim(
            claimId = claimId,
            submittedBy = UserId(first[SourceRecordTable.submittedBy]),
            document = SourceDocument(
                SourceDescriptor(first[SourceRecordTable.sourceType], first[SourceRecordTable.sourceName]),
                sourceRecords(rows),
            ),
        )
    }

    override fun markFailed(claimId: String, error: String, now: Long): Boolean = db.write {
        SourceRecordExtractionTable.update({
            (SourceRecordExtractionTable.claimId eq claimId) and (SourceRecordExtractionTable.status eq PROCESSING)
        }) {
            it[status] = FAILED
            it[lastError] = error.take(MAX_ERROR_LENGTH)
            it[updatedAt] = now
        } > 0
    }

    /** The stored records matching [condition] with their extraction state, oldest recorded first. */
    private fun readyRecords(condition: Op<Boolean>): Query =
        SourceRecordTable.join(
            SourceRecordExtractionTable,
            JoinType.LEFT,
            SourceRecordTable.id,
            SourceRecordExtractionTable.sourceRecordId,
        ).selectAll()
            .where { condition }
            .orderBy(SourceRecordTable.recordedAt to SortOrder.ASC, SourceRecordTable.id to SortOrder.ASC)

    /** The records of [row]'s source that [row]'s submitter submitted. */
    private fun sameSourceAndSubmitter(row: ResultRow): Op<Boolean> = SqlExpressionBuilder.run {
        (SourceRecordTable.sourceType eq row[SourceRecordTable.sourceType]) and
            (SourceRecordTable.sourceName eq row[SourceRecordTable.sourceName]) and
            (SourceRecordTable.submittedBy eq row[SourceRecordTable.submittedBy])
    }

    /** The source records stored in [rows], read inside the caller's transaction. */
    private fun sourceRecords(rows: List<ResultRow>): List<SourceRecord> =
        with(SourceRecordRepositoryImpl(db)) { rows.map { it.toSourceRecord() } }

    private fun readyCondition(retryBefore: Long, staleProcessingBefore: Long, maxAttempts: Int): Op<Boolean> =
        SqlExpressionBuilder.run {
            SourceRecordExtractionTable.status.isNull() or
                ((SourceRecordExtractionTable.status eq FAILED) and
                    (SourceRecordExtractionTable.updatedAt lessEq retryBefore) and
                    (SourceRecordExtractionTable.attempts less maxAttempts)) or
                ((SourceRecordExtractionTable.status eq PROCESSING) and
                    (SourceRecordExtractionTable.updatedAt lessEq staleProcessingBefore))
        }

    /** Hands [recordIds] to claim [claimId] and counts the attempt. */
    private fun claim(recordIds: List<Int>, claimId: String, now: Long) {
        addMissingRows(recordIds, now)
        SourceRecordExtractionTable.update({ SourceRecordExtractionTable.sourceRecordId inList recordIds }) {
            it[status] = PROCESSING
            it[attempts] = attempts + 1
            it[SourceRecordExtractionTable.claimId] = claimId
            it[updatedAt] = now
        }
    }

    companion object {
        /**
         * Takes [recordIds] off the queue inside the caller's transaction, since the Episodes extracted from them are
         * being stored in it.
         */
        internal fun markExtracted(recordIds: Collection<Int>, now: Long) {
            addMissingRows(recordIds, now)
            SourceRecordExtractionTable.update({ SourceRecordExtractionTable.sourceRecordId inList recordIds }) {
                it[status] = EXTRACTED
                it[claimId] = null
                it[lastError] = null
                it[updatedAt] = now
            }
        }

        /** Adds a row for each of [recordIds] that has none yet, for the caller to update; an existing row is kept. */
        private fun addMissingRows(recordIds: Collection<Int>, now: Long) {
            SourceRecordExtractionTable.batchInsert(recordIds, ignore = true) { recordId ->
                this[SourceRecordExtractionTable.sourceRecordId] = recordId
                this[SourceRecordExtractionTable.status] = PROCESSING
                this[SourceRecordExtractionTable.attempts] = 0
                this[SourceRecordExtractionTable.claimId] = null
                this[SourceRecordExtractionTable.lastError] = null
                this[SourceRecordExtractionTable.updatedAt] = now
            }
        }

        private const val MAX_ERROR_LENGTH = 2_000
    }
}
