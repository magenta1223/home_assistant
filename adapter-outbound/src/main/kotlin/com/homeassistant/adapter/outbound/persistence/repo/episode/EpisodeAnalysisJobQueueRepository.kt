package com.homeassistant.adapter.outbound.persistence.repo.episode

import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeAnalysisJobRecordTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeAnalysisJobTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeAnalysisJobViewerTable
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeAnalysisJob
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeAnalysisJobQueue
import com.homeassistant.domain.episode.EpisodeAnalysisJobStatus.COMPLETED
import com.homeassistant.domain.episode.EpisodeAnalysisJobStatus.FAILED
import com.homeassistant.domain.episode.EpisodeAnalysisJobStatus.PENDING
import com.homeassistant.domain.episode.EpisodeAnalysisJobStatus.PROCESSING
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeAnalysisSubmission
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.access.Visibility
import com.homeassistant.domain.episode.EpisodeAnalysisJobStatus
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

internal class EpisodeAnalysisJobQueueRepository(private val db: Database) : EpisodeAnalysisJobQueue {
    override fun enqueue(submission: EpisodeAnalysisSubmission, now: Long): EpisodeAnalysisJob = transaction(db) {
        val jobId = EpisodeAnalysisJobTable.insert {
            it[requestedByUserId] = submission.requestedBy.value
            it[sourceType] = submission.source.type
            it[sourceName] = submission.source.name
            it[referenceId] = submission.referenceId
            it[visibility] = submission.access.visibility.name
            it[status] = PENDING.name
            it[attempts] = 0
            it[episodeCount] = null
            it[lastError] = null
            it[updatedAt] = now
        }[EpisodeAnalysisJobTable.id]
        EpisodeAnalysisJobRecordTable.batchInsert(submission.sourceRecordIds.distinct()) { sourceRecordId ->
            this[EpisodeAnalysisJobRecordTable.jobId] = jobId
            this[EpisodeAnalysisJobRecordTable.sourceRecordId] = sourceRecordId
        }
        EpisodeAnalysisJobViewerTable.batchInsert(submission.access.allowedUserIds) { allowedUserId ->
            this[EpisodeAnalysisJobViewerTable.jobId] = jobId
            this[EpisodeAnalysisJobViewerTable.userId] = allowedUserId
        }
        findJob(EpisodeAnalysisJobTable.id eq jobId)!!
    }

    override fun claimNext(
        now: Long,
        retryBefore: Long,
        staleProcessingBefore: Long,
        maxAttempts: Int,
    ): EpisodeAnalysisJob? = transaction(db) {
        require(maxAttempts > 0)
        val ready = readyCondition(retryBefore, staleProcessingBefore, maxAttempts)
        val row = EpisodeAnalysisJobTable.selectAll()
            .where { ready }
            .orderBy(EpisodeAnalysisJobTable.id)
            .limit(1)
            .singleOrNull() ?: return@transaction null
        val jobId = row[EpisodeAnalysisJobTable.id]
        val claimed = EpisodeAnalysisJobTable.update({ (EpisodeAnalysisJobTable.id eq jobId) and ready }) {
            it[status] = PROCESSING.name
            it[attempts] = row[EpisodeAnalysisJobTable.attempts] + 1
            it[updatedAt] = now
        }
        if (claimed == 0) null else findJob(EpisodeAnalysisJobTable.id eq jobId)
    }

    override fun markCompleted(jobId: Int, expectedAttempt: Int, episodeCount: Int, now: Long): Boolean =
        transition(jobId, expectedAttempt, COMPLETED, episodeCount, null, now)

    override fun markFailed(jobId: Int, expectedAttempt: Int, error: String, now: Long): Boolean =
        transition(jobId, expectedAttempt, FAILED, null, error.take(MAX_ERROR_LENGTH), now)

    override fun findRequestedBy(jobId: Int, userId: UserId): EpisodeAnalysisJob? = transaction(db) {
        findJob((EpisodeAnalysisJobTable.id eq jobId) and (EpisodeAnalysisJobTable.requestedByUserId eq userId.value))
    }

    private fun transition(
        jobId: Int,
        expectedAttempt: Int,
        next: EpisodeAnalysisJobStatus,
        episodeCount: Int?,
        error: String?,
        now: Long,
    ): Boolean = transaction(db) {
        EpisodeAnalysisJobTable.update({
            (EpisodeAnalysisJobTable.id eq jobId) and
                (EpisodeAnalysisJobTable.status eq PROCESSING.name) and
                (EpisodeAnalysisJobTable.attempts eq expectedAttempt)
        }) {
            it[status] = next.name
            it[EpisodeAnalysisJobTable.episodeCount] = episodeCount
            it[lastError] = error
            it[updatedAt] = now
        } == 1
    }

    /** The job matching [condition] with its source records, oldest first, read inside the caller's transaction. */
    private fun findJob(condition: Op<Boolean>): EpisodeAnalysisJob? =
        EpisodeAnalysisJobTable.selectAll().where { condition }.singleOrNull()?.let { row ->
            val jobId = row[EpisodeAnalysisJobTable.id]
            val sourceRecordIds = EpisodeAnalysisJobRecordTable.select(EpisodeAnalysisJobRecordTable.sourceRecordId)
                .where { EpisodeAnalysisJobRecordTable.jobId eq jobId }
                .orderBy(EpisodeAnalysisJobRecordTable.sourceRecordId)
                .map { it[EpisodeAnalysisJobRecordTable.sourceRecordId] }
            EpisodeAnalysisJob(
                id = jobId,
                submission = EpisodeAnalysisSubmission(
                    requestedBy = UserId(row[EpisodeAnalysisJobTable.requestedByUserId]),
                    source = SourceDescriptor(row[EpisodeAnalysisJobTable.sourceType], row[EpisodeAnalysisJobTable.sourceName]),
                    sourceRecordIds = sourceRecordIds,
                    referenceId = row[EpisodeAnalysisJobTable.referenceId],
                    access = row.toAccess(),
                ),
                status = EpisodeAnalysisJobStatus.valueOf(row[EpisodeAnalysisJobTable.status]),
                attempt = row[EpisodeAnalysisJobTable.attempts],
                episodeCount = row[EpisodeAnalysisJobTable.episodeCount],
                lastError = row[EpisodeAnalysisJobTable.lastError],
            )
        }

    /** The audience the job's records are stored for, read inside the caller's transaction. */
    private fun ResultRow.toAccess(): AccessControl {
        val visibility = Visibility.valueOf(this[EpisodeAnalysisJobTable.visibility])
        val viewers = EpisodeAnalysisJobViewerTable.select(EpisodeAnalysisJobViewerTable.userId)
            .where { EpisodeAnalysisJobViewerTable.jobId eq this@toAccess[EpisodeAnalysisJobTable.id] }
            .mapTo(linkedSetOf()) { it[EpisodeAnalysisJobViewerTable.userId] }
        return AccessControl(visibility, viewers)
    }

    private fun readyCondition(retryBefore: Long, staleProcessingBefore: Long, maxAttempts: Int): Op<Boolean> =
        SqlExpressionBuilder.run {
            (EpisodeAnalysisJobTable.status eq PENDING.name) or
                ((EpisodeAnalysisJobTable.status eq FAILED.name) and
                    (EpisodeAnalysisJobTable.updatedAt lessEq retryBefore) and
                    (EpisodeAnalysisJobTable.attempts less maxAttempts)) or
                ((EpisodeAnalysisJobTable.status eq PROCESSING.name) and
                    (EpisodeAnalysisJobTable.updatedAt lessEq staleProcessingBefore))
        }

    private companion object {
        const val MAX_ERROR_LENGTH = 2_000
    }
}
