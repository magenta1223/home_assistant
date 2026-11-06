package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable.COMPLETED
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable.FAILED
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable.PENDING
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable.PROCESSING
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentExtractionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeEvidenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepositoryImpl
import com.homeassistant.application.port.outbound.memoryextraction.component.PendingEpisode
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentAssignmentOutbox
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentAssignmentTask
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class ComponentAssignmentOutboxRepository(private val db: Database) : ComponentAssignmentOutbox {
    override fun claimReady(
        limit: Int,
        now: Long,
        retryBefore: Long,
        staleProcessingBefore: Long,
        maxAttempts: Int,
    ): List<ComponentAssignmentTask> = transaction(db) {
        require(limit > 0 && maxAttempts > 0)
        val ready = readyCondition(retryBefore, staleProcessingBefore, maxAttempts)
        val rows = (ComponentAssignmentOutboxTable innerJoin EpisodeTable).selectAll()
            .where { ready }
            .orderBy(ComponentAssignmentOutboxTable.id)
            .limit(limit)
            .toList()
        rows.mapNotNull { row ->
            val outboxId = row[ComponentAssignmentOutboxTable.id]
            val episodeId = row[ComponentAssignmentOutboxTable.episodeId]
            if (isExtracted(episodeId)) {
                ComponentAssignmentOutboxTable.update({ ComponentAssignmentOutboxTable.id eq outboxId }) {
                    it[status] = COMPLETED
                    it[lastError] = null
                    it[updatedAt] = now
                }
                return@mapNotNull null
            }
            val attempt = row[ComponentAssignmentOutboxTable.attempts] + 1
            val claimed = ComponentAssignmentOutboxTable.update({ (ComponentAssignmentOutboxTable.id eq outboxId) and ready }) {
                it[status] = PROCESSING
                it[attempts] = attempt
                it[updatedAt] = now
            }
            if (claimed == 0) return@mapNotNull null
            ComponentAssignmentTask(outboxId, attempt, pendingEpisode(row))
        }
    }

    override fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long): Boolean =
        transition(outboxId, expectedAttempt, COMPLETED, null, now)

    override fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long): Boolean =
        transition(outboxId, expectedAttempt, FAILED, error.take(MAX_ERROR_LENGTH), now)

    override fun enqueueUnassigned(now: Long): Int = transaction(db) {
        val episodeIds = EpisodeTable.select(EpisodeTable.id).where {
            notExists(ComponentExtractionTable.selectAll().where { ComponentExtractionTable.episodeId eq EpisodeTable.id }) and
                notExists(ComponentAssignmentOutboxTable.selectAll().where { ComponentAssignmentOutboxTable.episodeId eq EpisodeTable.id })
        }.map { it[EpisodeTable.id] }
        episodeIds.forEach { enqueue(it, now) }
        episodeIds.size
    }

    private fun transition(outboxId: Int, expectedAttempt: Int, next: String, error: String?, now: Long): Boolean =
        transaction(db) {
            ComponentAssignmentOutboxTable.update({
                (ComponentAssignmentOutboxTable.id eq outboxId) and
                    (ComponentAssignmentOutboxTable.status eq PROCESSING) and
                    (ComponentAssignmentOutboxTable.attempts eq expectedAttempt)
            }) {
                it[status] = next
                it[lastError] = error
                it[updatedAt] = now
            } == 1
        }

    private fun isExtracted(episodeId: Int): Boolean =
        ComponentExtractionTable.select(ComponentExtractionTable.episodeId).where { ComponentExtractionTable.episodeId eq episodeId }.any()

    /** The episode with the messages it was written from, oldest first, read from the same transaction as its claim. */
    private fun pendingEpisode(row: ResultRow): PendingEpisode {
        val episode = with(EpisodeRepositoryImpl(db)) { row.toEpisode() }
        val messages = (EpisodeEvidenceTable innerJoin SourceRecordTable).select(SourceRecordTable.content)
            .where { EpisodeEvidenceTable.episodeId eq episode.id }
            .orderBy(SourceRecordTable.id)
            .map { it[SourceRecordTable.content] }
        return PendingEpisode(episode.id, episode.title, episode.content, episode.access, messages)
    }

    private fun readyCondition(retryBefore: Long, staleProcessingBefore: Long, maxAttempts: Int): Op<Boolean> =
        SqlExpressionBuilder.run {
            (ComponentAssignmentOutboxTable.status eq PENDING) or
                ((ComponentAssignmentOutboxTable.status eq FAILED) and
                    (ComponentAssignmentOutboxTable.updatedAt lessEq retryBefore) and
                    (ComponentAssignmentOutboxTable.attempts less maxAttempts)) or
                ((ComponentAssignmentOutboxTable.status eq PROCESSING) and
                    (ComponentAssignmentOutboxTable.updatedAt lessEq staleProcessingBefore))
        }

    companion object {
        /** Adds the queue row for an episode inside the caller's transaction; an existing row is kept. */
        internal fun enqueue(episodeId: Int, now: Long) {
            ComponentAssignmentOutboxTable.insertIgnore {
                it[ComponentAssignmentOutboxTable.episodeId] = episodeId
                it[status] = PENDING
                it[attempts] = 0
                it[lastError] = null
                it[updatedAt] = now
            }
        }

        private const val MAX_ERROR_LENGTH = 2_000
    }
}
