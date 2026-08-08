package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryEvidenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.application.memory.read.MemoryReader
import com.homeassistant.application.memory.tree.MemoryTreeAttachRequest
import com.homeassistant.application.memory.tree.MemoryTreeAttachResponse
import com.homeassistant.application.memory.tree.MemoryTreeStore
import com.homeassistant.application.memory.write.MemoryWriter
import com.homeassistant.common.json.JsonSerializer.decodeFromString
import com.homeassistant.common.json.JsonSerializer.encodeToString
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock

internal class MemoryRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : MemoryReader, MemoryWriter, MemoryTreeStore {

    override fun getMemories(userId: UserId): List<Memory> {
        return MemoryTable.selectAll()
            .map { it.toMemory() }
            .filter { it.isVisibleTo(userId) }

    }

    override fun write(proposal: MemoryProposal, createdBy: UserId): Memory = transaction(db) {
        require(proposal.evidenceIds.isNotEmpty()) { "memory evidence is required" }
        val now = clock.millis()
        val memoryId = MemoryTable.insert {
            it[childrenIds] = emptyList<Int>().encodeToString()
            it[createdByUserId] = createdBy.value
            it[content] = proposal.content
            it[subject] = proposal.subject
            it[memoryType] = proposal.memoryType.name
            it[certainty] = proposal.certainty.name
            it[visibility] = proposal.visibility.name
            it[createdAt] = now
            it[updatedAt] = now
        }[MemoryTable.id]
        proposal.evidenceIds.distinct().forEach { sourceRecordId ->
            MemoryEvidenceTable.insert {
                it[MemoryEvidenceTable.memoryId] = memoryId
                it[MemoryEvidenceTable.sourceRecordId] = sourceRecordId
            }
        }
        proposal.toMemory(createdBy, memoryId, now)
    }

    override fun attachChildren(request: MemoryTreeAttachRequest): MemoryTreeAttachResponse = transaction(db) {
        if (request.parentByChild.isEmpty()) return@transaction MemoryTreeAttachResponse(emptyList())

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

        val updatedParentIds = linkedSetOf<Int>()
        request.parentByChild.values.distinct().forEach { parentId ->
            val parent = all.getValue(parentId)
            val children = finalChildren.getValue(parentId)
            if (children != parent.childrenIds) {
                MemoryTable.update({ MemoryTable.id eq parentId }) {
                    it[childrenIds] = children.encodeToString()
                    it[updatedAt] = System.currentTimeMillis()
                }
                updatedParentIds += parentId
            }
        }

        MemoryTreeAttachResponse(
            updatedMemories = getMemories(request.userId).filter { it.id in updatedParentIds },
        )
    }

    private fun ResultRow.toMemory(): Memory {
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
}
