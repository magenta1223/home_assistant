package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable.COMPLETED
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable.FAILED
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable.PENDING
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable.PROCESSING
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentMembershipTable
import com.homeassistant.application.port.output.component.PendingEpisode
import com.homeassistant.application.port.output.component.ComponentAssignmentOutbox
import com.homeassistant.application.port.output.component.ComponentAssignmentTask
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
            if (isAssigned(episodeId)) {
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
            ComponentAssignmentTask(outboxId, attempt, PendingEpisode(episodeId, row[EpisodeTable.content]))
        }
    }

    override fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long): Boolean =
        transition(outboxId, expectedAttempt, COMPLETED, null, now)

    override fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long): Boolean =
        transition(outboxId, expectedAttempt, FAILED, error.take(MAX_ERROR_LENGTH), now)

    override fun enqueueUnassigned(now: Long): Int = transaction(db) {
        val episodeIds = EpisodeTable.select(EpisodeTable.id).where {
            notExists(ComponentMembershipTable.selectAll().where { ComponentMembershipTable.episodeId eq EpisodeTable.id }) and
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

    private fun isAssigned(episodeId: Int): Boolean =
        ComponentMembershipTable.select(ComponentMembershipTable.episodeId).where { ComponentMembershipTable.episodeId eq episodeId }.limit(1).any()

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
