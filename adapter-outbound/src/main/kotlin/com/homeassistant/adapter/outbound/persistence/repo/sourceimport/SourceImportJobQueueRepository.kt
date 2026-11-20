package com.homeassistant.adapter.outbound.persistence.repo.sourceimport

import com.homeassistant.adapter.outbound.persistence.db.SqliteDatabases
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceImportJobRecordTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceImportJobTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceImportJobViewerTable
import com.homeassistant.application.port.outbound.sourceimport.SourceImportJob
import com.homeassistant.application.port.outbound.sourceimport.SourceImportJobQueue
import com.homeassistant.domain.source.SourceImportJobStatus.COMPLETED
import com.homeassistant.domain.source.SourceImportJobStatus.FAILED
import com.homeassistant.domain.source.SourceImportJobStatus.PENDING
import com.homeassistant.domain.source.SourceImportJobStatus.PROCESSING
import com.homeassistant.application.port.outbound.sourceimport.SourceImportSubmission
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.access.Visibility
import com.homeassistant.domain.source.SourceImportJobStatus
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

internal class SourceImportJobQueueRepository(private val db: SqliteDatabases) : SourceImportJobQueue {
    override fun enqueue(submission: SourceImportSubmission, now: Long): SourceImportJob = db.write {
        val jobId = SourceImportJobTable.insert {
            it[requestedByUserId] = submission.requestedBy.value
            it[sourceType] = submission.source.type
            it[sourceName] = submission.source.name
            it[referenceId] = submission.referenceId
            it[visibility] = submission.access.visibility.name
            it[status] = PENDING.name
            it[attempts] = 0
            it[recordCount] = null
            it[lastError] = null
            it[updatedAt] = now
        }[SourceImportJobTable.id]
        SourceImportJobRecordTable.batchInsert(submission.sourceRecordIds.distinct()) { sourceRecordId ->
            this[SourceImportJobRecordTable.jobId] = jobId
            this[SourceImportJobRecordTable.sourceRecordId] = sourceRecordId
        }
        SourceImportJobViewerTable.batchInsert(submission.access.allowedUserIds) { allowedUserId ->
            this[SourceImportJobViewerTable.jobId] = jobId
            this[SourceImportJobViewerTable.userId] = allowedUserId
        }
        findJob(SourceImportJobTable.id eq jobId)!!
    }

    override fun claimNext(
        now: Long,
        retryBefore: Long,
        staleProcessingBefore: Long,
        maxAttempts: Int,
    ): SourceImportJob? = db.write {
        require(maxAttempts > 0)
        val ready = readyCondition(retryBefore, staleProcessingBefore, maxAttempts)
        val row = SourceImportJobTable.selectAll()
            .where { ready }
            .orderBy(SourceImportJobTable.id)
            .limit(1)
            .singleOrNull() ?: return@write null
        val jobId = row[SourceImportJobTable.id]
        val claimed = SourceImportJobTable.update({ (SourceImportJobTable.id eq jobId) and ready }) {
            it[status] = PROCESSING.name
            it[attempts] = row[SourceImportJobTable.attempts] + 1
            it[updatedAt] = now
        }
        if (claimed == 0) null else findJob(SourceImportJobTable.id eq jobId)
    }

    override fun markCompleted(jobId: Int, expectedAttempt: Int, recordCount: Int, now: Long): Boolean =
        transition(jobId, expectedAttempt, COMPLETED, recordCount, null, now)

    override fun markFailed(jobId: Int, expectedAttempt: Int, error: String, now: Long): Boolean =
        transition(jobId, expectedAttempt, FAILED, null, error.take(MAX_ERROR_LENGTH), now)

    override fun findRequestedBy(jobId: Int, userId: UserId): SourceImportJob? = db.read {
        findJob((SourceImportJobTable.id eq jobId) and (SourceImportJobTable.requestedByUserId eq userId.value))
    }

    private fun transition(
        jobId: Int,
        expectedAttempt: Int,
        next: SourceImportJobStatus,
        recordCount: Int?,
        error: String?,
        now: Long,
    ): Boolean = db.write {
        SourceImportJobTable.update({
            (SourceImportJobTable.id eq jobId) and
                (SourceImportJobTable.status eq PROCESSING.name) and
                (SourceImportJobTable.attempts eq expectedAttempt)
        }) {
            it[status] = next.name
            it[SourceImportJobTable.recordCount] = recordCount
            it[lastError] = error
            it[updatedAt] = now
        } == 1
    }

    /** The job matching [condition] with its source records, oldest first, read inside the caller's transaction. */
    private fun findJob(condition: Op<Boolean>): SourceImportJob? =
        SourceImportJobTable.selectAll().where { condition }.singleOrNull()?.let { row ->
            val jobId = row[SourceImportJobTable.id]
            val sourceRecordIds = SourceImportJobRecordTable.select(SourceImportJobRecordTable.sourceRecordId)
                .where { SourceImportJobRecordTable.jobId eq jobId }
                .orderBy(SourceImportJobRecordTable.sourceRecordId)
                .map { it[SourceImportJobRecordTable.sourceRecordId] }
            SourceImportJob(
                id = jobId,
                submission = SourceImportSubmission(
                    requestedBy = UserId(row[SourceImportJobTable.requestedByUserId]),
                    source = SourceDescriptor(row[SourceImportJobTable.sourceType], row[SourceImportJobTable.sourceName]),
                    sourceRecordIds = sourceRecordIds,
                    referenceId = row[SourceImportJobTable.referenceId],
                    access = row.toAccess(),
                ),
                status = SourceImportJobStatus.valueOf(row[SourceImportJobTable.status]),
                attempt = row[SourceImportJobTable.attempts],
                recordCount = row[SourceImportJobTable.recordCount],
                lastError = row[SourceImportJobTable.lastError],
            )
        }

    /** The audience the job's records are stored for, read inside the caller's transaction. */
    private fun ResultRow.toAccess(): AccessControl {
        val visibility = Visibility.valueOf(this[SourceImportJobTable.visibility])
        val viewers = SourceImportJobViewerTable.select(SourceImportJobViewerTable.userId)
            .where { SourceImportJobViewerTable.jobId eq this@toAccess[SourceImportJobTable.id] }
            .mapTo(linkedSetOf()) { it[SourceImportJobViewerTable.userId] }
        return AccessControl(visibility, viewers)
    }

    private fun readyCondition(retryBefore: Long, staleProcessingBefore: Long, maxAttempts: Int): Op<Boolean> =
        SqlExpressionBuilder.run {
            (SourceImportJobTable.status eq PENDING.name) or
                ((SourceImportJobTable.status eq FAILED.name) and
                    (SourceImportJobTable.updatedAt lessEq retryBefore) and
                    (SourceImportJobTable.attempts less maxAttempts)) or
                ((SourceImportJobTable.status eq PROCESSING.name) and
                    (SourceImportJobTable.updatedAt lessEq staleProcessingBefore))
        }

    private companion object {
        const val MAX_ERROR_LENGTH = 2_000
    }
}
