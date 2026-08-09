package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryEvidenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.adapter.outbound.persistence.db.tables.IndexingOutboxTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.memory.placement.MemoryTreeAttachRequest
import com.homeassistant.application.port.output.memory.placement.MemoryTreeStore
import com.homeassistant.application.port.output.memory.write.CanonicalMemoryBatchWriter
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.common.json.JsonSerializer.decodeFromString
import com.homeassistant.common.json.JsonSerializer.encodeToString
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock

internal class MemoryRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : MemoryReader, CanonicalMemoryBatchWriter, MemoryTreeStore {

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
                val memoryId = MemoryTable.insert {
                    it[childrenIds] = emptyList<Int>().encodeToString()
                    it[createdByUserId] = createdBy.value
                    it[content] = proposal.content
                    it[subject] = proposal.subject
                    it[memoryType] = proposal.memoryType.name
                    it[certainty] = proposal.certainty.name
                    it[visibility] = proposal.visibility.name
                    it[idempotencyKey] = item.idempotencyKey
                    it[createdAt] = now
                    it[updatedAt] = now
                }[MemoryTable.id]
                proposal.evidenceIds.distinct().forEach { sourceRecordId ->
                    MemoryEvidenceTable.insert {
                        it[MemoryEvidenceTable.memoryId] = memoryId
                        it[MemoryEvidenceTable.sourceRecordId] = sourceRecordId
                    }
                }
                ensureOutbox(memoryId, now)
                proposal.toMemory(createdBy, memoryId, now)
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
        val exists = IndexingOutboxTable.select(IndexingOutboxTable.id)
            .where {
                (IndexingOutboxTable.targetType eq MEMORY_TARGET_TYPE) and
                    (IndexingOutboxTable.targetId eq memoryId)
            }
            .limit(1)
            .any()
        if (!exists) {
            IndexingOutboxTable.insert {
                it[targetType] = MEMORY_TARGET_TYPE
                it[targetId] = memoryId
                it[status] = OUTBOX_PENDING
                it[attempts] = 0
                it[lastError] = null
                it[updatedAt] = now
            }
        }
    }

    override fun attachChildren(request: MemoryTreeAttachRequest): Unit = transaction(db) {
        if (request.parentByChild.isEmpty()) return@transaction

        val all = getMemories(request.userId).associateBy { it.id }
        request.parentByChild.forEach { (childMemoryId, parentMemoryId) ->
            require(parentMemoryId != childMemoryId) {
                "A memory cannot contain itself as a child"
            }
            require(all.containsKey(parentMemoryId)) {
                "Memory parent does not exist: $parentMemoryId"
            }
            require(all.containsKey(childMemoryId)) {
                "Memory does not exist: $childMemoryId"
            }
            val existingContainer = all.values.firstOrNull {
                it.id != parentMemoryId && childMemoryId in it.childrenIds
            }
            require(existingContainer == null) {
                "Memory already belongs to another parent: $childMemoryId"
            }
        }

        val finalChildren = all.mapValues { (_, memory) -> memory.childrenIds.toMutableList() }.toMutableMap()
        request.parentByChild.forEach { (childMemoryId, parentMemoryId) ->
            val children = finalChildren.getValue(parentMemoryId)
            if (childMemoryId !in children) {
                children += childMemoryId
            }
        }

        val visiting = mutableSetOf<Int>()
        val visited = mutableSetOf<Int>()
        fun verifyAcyclic(memoryId: Int) {
            if (memoryId in visited) return
            require(visiting.add(memoryId)) {
                "Placement assignments would create a cycle at memory=$memoryId"
            }
            finalChildren[memoryId].orEmpty().forEach(::verifyAcyclic)
            visiting.remove(memoryId)
            visited += memoryId
        }
        all.keys.forEach(::verifyAcyclic)

        request.parentByChild.values.distinct().forEach { parentId ->
            val parent = all.getValue(parentId)
            val children = finalChildren.getValue(parentId)
            if (children != parent.childrenIds) {
                MemoryTable.update({ MemoryTable.id eq parentId }) {
                    it[childrenIds] = children.encodeToString()
                    it[updatedAt] = System.currentTimeMillis()
                }
            }
        }
    }

    internal fun ResultRow.toMemory(): Memory {
        val memoryId = this[MemoryTable.id]
        val evidenceRefs = MemoryEvidenceTable.select(MemoryEvidenceTable.sourceRecordId)
            .where { MemoryEvidenceTable.memoryId eq memoryId }
            .map { it[MemoryEvidenceTable.sourceRecordId] }
        val children = runCatching { this[MemoryTable.childrenIds].decodeFromString<List<Int>>() }
            .getOrDefault(emptyList())
        return Memory(
            id = memoryId,
            childrenIds = children,
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

    private fun MemoryProposal.toMemory(userId: UserId, memoryId: Int, createdAt: Long): Memory {
        return Memory(
            id = memoryId,
            childrenIds = emptyList(),
            createdByUserId = userId.value,
            content = content,
            subject = subject,
            memoryType = memoryType,
            certainty = certainty,
            visibility = visibility,
            evidenceRefs = evidenceIds,
            createdAt = createdAt,
        )
    }

    private companion object {
        const val MEMORY_TARGET_TYPE = "CANONICAL_MEMORY"
        const val OUTBOX_PENDING = "PENDING"
    }
}
