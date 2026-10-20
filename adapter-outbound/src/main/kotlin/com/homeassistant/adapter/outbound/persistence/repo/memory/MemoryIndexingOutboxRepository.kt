package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.db.tables.IndexingOutboxTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryEvidenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryViewerTable
import com.homeassistant.application.port.output.memory.write.MemoryIndexingOutbox
import com.homeassistant.application.port.output.memory.write.MemoryIndexingTask
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryVisibility
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class MemoryIndexingOutboxRepository(
    private val db: Database,
) : MemoryIndexingOutbox {
    override fun claimReady(
        limit: Int,
        now: Long,
        retryBefore: Long,
        staleProcessingBefore: Long,
    ): List<MemoryIndexingTask> = transaction(db) {
        val ready = readyCondition(retryBefore, staleProcessingBefore)
        val rows = IndexingOutboxTable.selectAll()
            .where { (IndexingOutboxTable.targetType eq MEMORY_TARGET_TYPE) and ready }
            .orderBy(IndexingOutboxTable.id)
            .limit(limit)
            .toList()

        rows.mapNotNull { row ->
            val outboxId = row[IndexingOutboxTable.id]
            val attempt = row[IndexingOutboxTable.attempts] + 1
            val claimed = IndexingOutboxTable.update({
                (IndexingOutboxTable.id eq outboxId) and readyCondition(retryBefore, staleProcessingBefore)
            }) {
                it[status] = OUTBOX_PROCESSING
                it[attempts] = attempt
                it[updatedAt] = now
            }
            if (claimed == 0) return@mapNotNull null

            val memory = MemoryTable.selectAll()
                .where { MemoryTable.id eq row[IndexingOutboxTable.targetId] }
                .single()
                .toMemory()
            MemoryIndexingTask(outboxId, attempt, memory)
        }
    }

    override fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long): Boolean = transaction(db) {
        IndexingOutboxTable.update({
            (IndexingOutboxTable.id eq outboxId) and
                (IndexingOutboxTable.status eq OUTBOX_PROCESSING) and
                (IndexingOutboxTable.attempts eq expectedAttempt)
        }) {
            it[status] = OUTBOX_COMPLETED
            it[lastError] = null
            it[updatedAt] = now
        } == 1
    }

    override fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long): Boolean = transaction(db) {
        IndexingOutboxTable.update({
            (IndexingOutboxTable.id eq outboxId) and
                (IndexingOutboxTable.status eq OUTBOX_PROCESSING) and
                (IndexingOutboxTable.attempts eq expectedAttempt)
        }) {
            it[status] = OUTBOX_FAILED
            it[lastError] = error.take(MAX_ERROR_LENGTH)
            it[updatedAt] = now
        } == 1
    }

    override fun enqueueAll(now: Long): Int = transaction(db) {
        val memoryIds = MemoryTable.select(MemoryTable.id).map { it[MemoryTable.id] }
        memoryIds.forEach { memoryId ->
            val reset = IndexingOutboxTable.update({
                (IndexingOutboxTable.targetType eq MEMORY_TARGET_TYPE) and
                    (IndexingOutboxTable.targetId eq memoryId)
            }) {
                it[status] = OUTBOX_PENDING
                it[lastError] = null
                it[updatedAt] = now
            }
            if (reset == 0) enqueue(memoryId, now)
        }
        memoryIds.size
    }

    private fun readyCondition(retryBefore: Long, staleProcessingBefore: Long): Op<Boolean> =
        SqlExpressionBuilder.run {
            (IndexingOutboxTable.status eq OUTBOX_PENDING) or
                ((IndexingOutboxTable.status eq OUTBOX_FAILED) and
                    (IndexingOutboxTable.updatedAt lessEq retryBefore)) or
                ((IndexingOutboxTable.status eq OUTBOX_PROCESSING) and
                    (IndexingOutboxTable.updatedAt lessEq staleProcessingBefore))
        }

    private fun ResultRow.toMemory(): Memory {
        val memoryId = this[MemoryTable.id]
        val evidenceRefs = MemoryEvidenceTable.select(MemoryEvidenceTable.sourceRecordId)
            .where { MemoryEvidenceTable.memoryId eq memoryId }
            .map { it[MemoryEvidenceTable.sourceRecordId] }
        return Memory(
            id = memoryId,
            createdByUserId = this[MemoryTable.createdByUserId],
            content = this[MemoryTable.content],
            visibility = MemoryVisibility.valueOf(this[MemoryTable.visibility]),
            allowedUserIds = MemoryViewerTable.select(MemoryViewerTable.userId)
                .where { MemoryViewerTable.memoryId eq memoryId }
                .mapTo(linkedSetOf()) { it[MemoryViewerTable.userId] },
            evidenceRefs = evidenceRefs,
            createdAt = this[MemoryTable.createdAt],
        )
    }

    companion object {
        /** Adds the queue row for a memory inside the caller's transaction; an existing row is kept. */
        internal fun enqueue(memoryId: Int, now: Long) {
            IndexingOutboxTable.insertIgnore {
                it[targetType] = MEMORY_TARGET_TYPE
                it[targetId] = memoryId
                it[status] = OUTBOX_PENDING
                it[attempts] = 0
                it[lastError] = null
                it[updatedAt] = now
            }
        }

        private const val MEMORY_TARGET_TYPE = "CANONICAL_MEMORY"
        private const val OUTBOX_PENDING = "PENDING"
        private const val OUTBOX_PROCESSING = "PROCESSING"
        private const val OUTBOX_COMPLETED = "COMPLETED"
        private const val OUTBOX_FAILED = "FAILED"
        private const val MAX_ERROR_LENGTH = 2_000
    }
}
