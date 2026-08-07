package com.homeassistant.adapter.outbound.persistence.repo.indexing

import com.homeassistant.application.memory.save.IndexTargetType
import com.homeassistant.application.memory.save.IndexingOutboxStore
import com.homeassistant.adapter.outbound.persistence.db.tables.IndexingOutboxTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

internal class IndexingOutboxRepository(
    private val db: Database,
) : IndexingOutboxStore {
    override fun pending(targetType: IndexTargetType, limit: Int): List<Int> = transaction(db) {
        IndexingOutboxTable.selectAll()
            .where {
                (IndexingOutboxTable.targetType eq targetType.name) and
                    (IndexingOutboxTable.status eq INDEX_PENDING)
            }
            .orderBy(IndexingOutboxTable.updatedAt to SortOrder.ASC)
            .limit(limit.coerceIn(1, 1000))
            .map { it[IndexingOutboxTable.targetId] }
    }

    override fun markIndexed(targetType: IndexTargetType, targetId: Int) {
        transaction(db) {
            IndexingOutboxTable.update({
                (IndexingOutboxTable.targetType eq targetType.name) and
                    (IndexingOutboxTable.targetId eq targetId)
            }) {
                it[status] = INDEXED
                it[lastError] = null
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    override fun markFailed(targetType: IndexTargetType, targetId: Int, error: String) {
        transaction(db) {
            val existing = IndexingOutboxTable.selectAll()
                .where {
                    (IndexingOutboxTable.targetType eq targetType.name) and
                        (IndexingOutboxTable.targetId eq targetId)
                }
                .singleOrNull()
            val now = System.currentTimeMillis()
            if (existing == null) {
                IndexingOutboxTable.insert {
                    it[IndexingOutboxTable.targetType] = targetType.name
                    it[IndexingOutboxTable.targetId] = targetId
                    it[status] = INDEX_PENDING
                    it[attempts] = 1
                    it[lastError] = error.take(MAX_ERROR_LENGTH)
                    it[updatedAt] = now
                }
            } else {
                IndexingOutboxTable.update({ IndexingOutboxTable.id eq existing[IndexingOutboxTable.id] }) {
                    it[status] = INDEX_PENDING
                    it[attempts] = existing[IndexingOutboxTable.attempts] + 1
                    it[lastError] = error.take(MAX_ERROR_LENGTH)
                    it[updatedAt] = now
                }
            }
        }
    }
}

private const val MAX_ERROR_LENGTH = 2_000
