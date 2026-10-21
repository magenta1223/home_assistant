package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicAssignmentOutboxTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicAssignmentOutboxTable.COMPLETED
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicAssignmentOutboxTable.FAILED
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicAssignmentOutboxTable.PENDING
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicAssignmentOutboxTable.PROCESSING
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicMembershipTable
import com.homeassistant.application.port.output.topic.PendingMemory
import com.homeassistant.application.port.output.topic.TopicAssignmentOutbox
import com.homeassistant.application.port.output.topic.TopicAssignmentTask
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class TopicAssignmentOutboxRepository(private val db: Database) : TopicAssignmentOutbox {
    override fun claimReady(
        limit: Int,
        now: Long,
        retryBefore: Long,
        staleProcessingBefore: Long,
        maxAttempts: Int,
    ): List<TopicAssignmentTask> = transaction(db) {
        require(limit > 0 && maxAttempts > 0)
        val ready = readyCondition(retryBefore, staleProcessingBefore, maxAttempts)
        val rows = (TopicAssignmentOutboxTable innerJoin MemoryTable).selectAll()
            .where { ready }
            .orderBy(TopicAssignmentOutboxTable.id)
            .limit(limit)
            .toList()
        rows.mapNotNull { row ->
            val outboxId = row[TopicAssignmentOutboxTable.id]
            val memoryId = row[TopicAssignmentOutboxTable.memoryId]
            if (isAssigned(memoryId)) {
                TopicAssignmentOutboxTable.update({ TopicAssignmentOutboxTable.id eq outboxId }) {
                    it[status] = COMPLETED
                    it[lastError] = null
                    it[updatedAt] = now
                }
                return@mapNotNull null
            }
            val attempt = row[TopicAssignmentOutboxTable.attempts] + 1
            val claimed = TopicAssignmentOutboxTable.update({ (TopicAssignmentOutboxTable.id eq outboxId) and ready }) {
                it[status] = PROCESSING
                it[attempts] = attempt
                it[updatedAt] = now
            }
            if (claimed == 0) return@mapNotNull null
            TopicAssignmentTask(outboxId, attempt, PendingMemory(memoryId, row[MemoryTable.content]))
        }
    }

    override fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long): Boolean =
        transition(outboxId, expectedAttempt, COMPLETED, null, now)

    override fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long): Boolean =
        transition(outboxId, expectedAttempt, FAILED, error.take(MAX_ERROR_LENGTH), now)

    override fun enqueueUnassigned(now: Long): Int = transaction(db) {
        val memoryIds = MemoryTable.select(MemoryTable.id).where {
            notExists(TopicMembershipTable.selectAll().where { TopicMembershipTable.memoryId eq MemoryTable.id }) and
                notExists(TopicAssignmentOutboxTable.selectAll().where { TopicAssignmentOutboxTable.memoryId eq MemoryTable.id })
        }.map { it[MemoryTable.id] }
        memoryIds.forEach { enqueue(it, now) }
        memoryIds.size
    }

    private fun transition(outboxId: Int, expectedAttempt: Int, next: String, error: String?, now: Long): Boolean =
        transaction(db) {
            TopicAssignmentOutboxTable.update({
                (TopicAssignmentOutboxTable.id eq outboxId) and
                    (TopicAssignmentOutboxTable.status eq PROCESSING) and
                    (TopicAssignmentOutboxTable.attempts eq expectedAttempt)
            }) {
                it[status] = next
                it[lastError] = error
                it[updatedAt] = now
            } == 1
        }

    private fun isAssigned(memoryId: Int): Boolean =
        TopicMembershipTable.select(TopicMembershipTable.memoryId).where { TopicMembershipTable.memoryId eq memoryId }.limit(1).any()

    private fun readyCondition(retryBefore: Long, staleProcessingBefore: Long, maxAttempts: Int): Op<Boolean> =
        SqlExpressionBuilder.run {
            (TopicAssignmentOutboxTable.status eq PENDING) or
                ((TopicAssignmentOutboxTable.status eq FAILED) and
                    (TopicAssignmentOutboxTable.updatedAt lessEq retryBefore) and
                    (TopicAssignmentOutboxTable.attempts less maxAttempts)) or
                ((TopicAssignmentOutboxTable.status eq PROCESSING) and
                    (TopicAssignmentOutboxTable.updatedAt lessEq staleProcessingBefore))
        }

    companion object {
        /** Adds the queue row for a memory inside the caller's transaction; an existing row is kept. */
        internal fun enqueue(memoryId: Int, now: Long) {
            TopicAssignmentOutboxTable.insertIgnore {
                it[TopicAssignmentOutboxTable.memoryId] = memoryId
                it[status] = PENDING
                it[attempts] = 0
                it[lastError] = null
                it[updatedAt] = now
            }
        }

        private const val MAX_ERROR_LENGTH = 2_000
    }
}
