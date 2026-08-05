package com.homeassistant.adapter.outbound.persistence.repo.indexing

import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.adapter.outbound.persistence.db.tables.IndexingOutboxTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

internal const val INDEX_PENDING = "INDEX_PENDING"
internal const val INDEXED = "INDEXED"

internal fun enqueueIndex(targetType: IndexTargetType, targetId: Int) {
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
            it[attempts] = 0
            it[lastError] = null
            it[updatedAt] = now
        }
    } else {
        IndexingOutboxTable.update({ IndexingOutboxTable.id eq existing[IndexingOutboxTable.id] }) {
            it[status] = INDEX_PENDING
            it[attempts] = 0
            it[lastError] = null
            it[updatedAt] = now
        }
    }
}
