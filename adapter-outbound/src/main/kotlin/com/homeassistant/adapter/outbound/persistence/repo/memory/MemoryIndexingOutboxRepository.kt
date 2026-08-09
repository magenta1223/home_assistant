package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.db.tables.IndexingOutboxTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryEvidenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.application.port.output.memory.write.MemoryIndexingOutbox
import com.homeassistant.application.port.output.memory.write.MemoryIndexingTask
import com.homeassistant.common.json.JsonSerializer.decodeFromString
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
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
            val claimed = IndexingOutboxTable.update({
                (IndexingOutboxTable.id eq outboxId) and readyCondition(retryBefore, staleProcessingBefore)
            }) {
                it[status] = OUTBOX_PROCESSING
                it[attempts] = row[IndexingOutboxTable.attempts] + 1
                it[updatedAt] = now
            }
            if (claimed == 0) return@mapNotNull null

            val memory = MemoryTable.selectAll()
                .where { MemoryTable.id eq row[IndexingOutboxTable.targetId] }
                .single()
                .toMemory()
            MemoryIndexingTask(outboxId, memory)
        }
    }

    override fun markCompleted(outboxId: Int, now: Long): Unit = transaction(db) {
        IndexingOutboxTable.update({ IndexingOutboxTable.id eq outboxId }) {
            it[status] = OUTBOX_COMPLETED
            it[lastError] = null
            it[updatedAt] = now
        }
    }

    override fun markFailed(outboxId: Int, error: String, now: Long): Unit = transaction(db) {
        IndexingOutboxTable.update({ IndexingOutboxTable.id eq outboxId }) {
            it[status] = OUTBOX_FAILED
            it[lastError] = error.take(MAX_ERROR_LENGTH)
            it[updatedAt] = now
        }
    }

    override fun enqueueAll(now: Long): Int = transaction(db) {
        val memoryIds = MemoryTable.select(MemoryTable.id).map { it[MemoryTable.id] }
        memoryIds.forEach { memoryId ->
            val existing = IndexingOutboxTable.selectAll()
                .where {
                    (IndexingOutboxTable.targetType eq MEMORY_TARGET_TYPE) and
                        (IndexingOutboxTable.targetId eq memoryId)
                }
                .singleOrNull()
            if (existing == null) {
                IndexingOutboxTable.insert {
                    it[targetType] = MEMORY_TARGET_TYPE
                    it[targetId] = memoryId
                    it[status] = OUTBOX_PENDING
                    it[attempts] = 0
                    it[lastError] = null
                    it[updatedAt] = now
                }
            } else {
                IndexingOutboxTable.update({ IndexingOutboxTable.id eq existing[IndexingOutboxTable.id] }) {
                    it[status] = OUTBOX_PENDING
                    it[attempts] = 0
                    it[lastError] = null
                    it[updatedAt] = now
                }
            }
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
            childrenIds = runCatching { this[MemoryTable.childrenIds].decodeFromString<List<Int>>() }
                .getOrDefault(emptyList()),
            createdByUserId = this[MemoryTable.createdByUserId],
            content = this[MemoryTable.content],
            subject = this[MemoryTable.subject],
            memoryType = MemoryType.valueOf(this[MemoryTable.memoryType]),
            certainty = MemoryCertainty.valueOf(this[MemoryTable.certainty]),
            visibility = MemoryVisibility.valueOf(this[MemoryTable.visibility]),
            evidenceRefs = evidenceRefs,
            createdAt = this[MemoryTable.createdAt],
        )
    }

    private companion object {
        const val MEMORY_TARGET_TYPE = "CANONICAL_MEMORY"
        const val OUTBOX_PENDING = "PENDING"
        const val OUTBOX_PROCESSING = "PROCESSING"
        const val OUTBOX_COMPLETED = "COMPLETED"
        const val OUTBOX_FAILED = "FAILED"
        const val MAX_ERROR_LENGTH = 2_000
    }
}
