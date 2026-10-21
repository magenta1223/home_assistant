package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryEvidenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryViewerTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordViewerTable
import com.homeassistant.adapter.outbound.persistence.repo.topic.TopicAssignmentOutboxRepository
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.memory.write.CanonicalMemoryBatchWriter
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock

internal class MemoryRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : MemoryReader, CanonicalMemoryBatchWriter {

    override fun getMemories(userId: UserId): List<Memory> = transaction(db) {
        MemoryTable.selectAll()
            .map { it.toMemory() }
            .filter { it.isVisibleTo(userId) }
    }

    override fun commit(
        createdBy: UserId,
        proposals: List<IdempotentMemoryProposal>,
        analyzedSourceRecordIds: Collection<Int>,
    ): List<Memory> = transaction(db) {
        val recordIds = analyzedSourceRecordIds.distinct()
        val existingRecordIds = if (recordIds.isEmpty()) emptySet() else {
            SourceRecordTable.select(SourceRecordTable.id)
                .where { SourceRecordTable.id inList recordIds }
                .mapTo(mutableSetOf()) { it[SourceRecordTable.id] }
        }
        require(existingRecordIds.size == recordIds.size) { "Analyzed source records do not all exist" }
        proposals.forEach { item ->
            require(item.idempotencyKey.isNotBlank()) { "memory idempotency key is required" }
            require(item.proposal.evidenceIds.isNotEmpty()) { "memory evidence is required" }
            require(item.proposal.evidenceIds.all { it in existingRecordIds }) {
                "Memory evidence must belong to the analyzed source batch"
            }
        }

        val accessByRecordId = existingRecordIds.associateWith(::sourceAccess)

        val now = clock.millis()
        val memories = proposals.map { item ->
            val existing = MemoryTable.selectAll()
                .where { MemoryTable.idempotencyKey eq item.idempotencyKey }
                .singleOrNull()
            if (existing != null) {
                ensureOutbox(existing[MemoryTable.id], now)
                existing.toMemory()
            } else {
                val proposal = item.proposal
                val access = MemoryAccess.intersection(
                    proposal.evidenceIds.distinct().map(accessByRecordId::getValue),
                )
                val memoryId = MemoryTable.insert {
                    it[createdByUserId] = createdBy.value
                    it[content] = proposal.content
                    it[visibility] = access.visibility.name
                    it[idempotencyKey] = item.idempotencyKey
                    it[createdAt] = now
                }[MemoryTable.id]
                proposal.evidenceIds.distinct().forEach { sourceRecordId ->
                    MemoryEvidenceTable.insert {
                        it[MemoryEvidenceTable.memoryId] = memoryId
                        it[MemoryEvidenceTable.sourceRecordId] = sourceRecordId
                    }
                }
                access.allowedUserIds.forEach { allowedUserId ->
                    MemoryViewerTable.insert {
                        it[MemoryViewerTable.memoryId] = memoryId
                        it[userId] = allowedUserId
                    }
                }
                ensureOutbox(memoryId, now)
                proposal.toMemory(createdBy, memoryId, now, access)
            }
        }
        if (recordIds.isNotEmpty()) {
            SourceRecordTable.update({ SourceRecordTable.id inList recordIds }) {
                it[analysisStatus] = SourceRecordAnalysisStatus.ANALYZED.name
            }
        }
        memories
    }

    private fun ensureOutbox(memoryId: Int, now: Long) {
        TopicAssignmentOutboxRepository.enqueue(memoryId, now)
    }

    internal fun ResultRow.toMemory(): Memory {
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

    private fun MemoryProposal.toMemory(
        userId: UserId,
        memoryId: Int,
        createdAt: Long,
        access: MemoryAccess,
    ): Memory {
        return Memory(
            id = memoryId,
            createdByUserId = userId.value,
            content = content,
            visibility = access.visibility,
            allowedUserIds = access.allowedUserIds,
            evidenceRefs = evidenceIds,
            createdAt = createdAt,
        )
    }

    private fun sourceAccess(sourceRecordId: Int): MemoryAccess {
        val visibility = SourceRecordTable.select(SourceRecordTable.visibility)
            .where { SourceRecordTable.id eq sourceRecordId }
            .single()[SourceRecordTable.visibility]
            .let(MemoryVisibility::valueOf)
        val viewers = if (visibility == MemoryVisibility.PUBLIC) emptySet() else {
            SourceRecordViewerTable.select(SourceRecordViewerTable.userId)
                .where { SourceRecordViewerTable.sourceRecordId eq sourceRecordId }
                .mapTo(linkedSetOf()) { it[SourceRecordViewerTable.userId] }
        }
        return MemoryAccess(visibility, viewers)
    }
}
