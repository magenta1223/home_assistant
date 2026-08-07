package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryEvidenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.adapter.outbound.persistence.repo.indexing.enqueueIndex
import com.homeassistant.application.memory.io.MemoryReader
import com.homeassistant.application.memory.save.MemoryCreator
import com.homeassistant.application.memory.save.IndexTargetType
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class MemoryRepository(
    private val db: Database,
) : MemoryReader, MemoryCreator {
    override fun findByIds(memoryIds: Collection<Int>): List<Memory> = transaction(db) {
        val ids = memoryIds.distinct()
        if (ids.isEmpty()) return@transaction emptyList()
        MemoryTable.selectAll()
            .where { MemoryTable.id inList ids }
            .map { row ->
                val memory = row.toMemory()
                memory
            }
    }

    override fun findVisibleByIds(userId: UserId, memoryIds: Collection<Int>): List<Memory> =
        findByIds(memoryIds).filter { it.isVisibleTo(userId) }

    override fun create(proposal: MemoryProposal, createdBy: UserId): Memory = transaction(db) {
        require(proposal.evidenceIds.isNotEmpty()) { "memory evidence is required" }
        val memoryId = MemoryTable.insert {
            it[parentId] = null
            it[createdByUserId] = createdBy.value
            it[content] = proposal.content
            it[subject] = proposal.subject
            it[memoryType] = proposal.memoryType.name
            it[certainty] = proposal.certainty.name
            it[visibility] = proposal.visibility.name
            it[createdAt] = System.currentTimeMillis()
            it[updatedAt] = System.currentTimeMillis()
        }[MemoryTable.id]
        proposal.evidenceIds.distinct().forEach { sourceRecordId ->
            MemoryEvidenceTable.insert {
                it[MemoryEvidenceTable.memoryId] = memoryId
                it[MemoryEvidenceTable.sourceRecordId] = sourceRecordId
            }
        }
        enqueueIndex(IndexTargetType.MEMORY, memoryId)
        findByIds(listOf(memoryId)).single()
    }

    private fun ResultRow.toMemory(): Memory {
        val memoryId = this[MemoryTable.id]
        val evidenceRefs = MemoryEvidenceTable.select(MemoryEvidenceTable.sourceRecordId)
            .where { MemoryEvidenceTable.memoryId eq memoryId }
            .map { it[MemoryEvidenceTable.sourceRecordId] }
        return Memory(
            id = memoryId,
            parentId = this[MemoryTable.parentId],
            createdByUserId = this[MemoryTable.createdByUserId],
            content = this[MemoryTable.content],
            subject = this[MemoryTable.subject],
            memoryType = MemoryType.valueOf(this[MemoryTable.memoryType]),
            certainty = MemoryCertainty.valueOf(this[MemoryTable.certainty]),
            visibility = MemoryVisibility.valueOf(this[MemoryTable.visibility]),
            evidenceRefs = evidenceRefs,
        )
    }
}
