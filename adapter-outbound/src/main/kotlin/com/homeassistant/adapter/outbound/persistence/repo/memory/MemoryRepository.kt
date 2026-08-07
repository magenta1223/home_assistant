package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryEvidenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.application.memory.io.MemoryReader
import com.homeassistant.application.memory.save.IndexTargetType
import com.homeassistant.application.memory.save.MemoryCreator
import com.homeassistant.application.memory.tree.MemoryTreeStore
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

internal class MemoryRepository(
    private val db: Database,
) : MemoryReader, MemoryCreator, MemoryTreeStore {

    override fun getMemories(userId: UserId): List<Memory> {
        return MemoryTable.selectAll()
            .map { it.toMemory() }
            .filter { it.isVisibleTo(userId) }

    }

    override fun create(proposal: MemoryProposal, createdBy: UserId): Memory = transaction(db) {
        require(proposal.evidenceIds.isNotEmpty()) { "memory evidence is required" }
        val memoryId = MemoryTable.insert {
            it[childrenIds] = emptyList<Int>().encodeToString()
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
        proposal.toMemory(createdBy, memoryId)
    }

    override fun attachChild(userId: UserId, parentMemoryId: Int, childMemoryId: Int): Memory = transaction(db) {
        require(parentMemoryId != childMemoryId) { "A memory cannot contain itself as a child" }
        val all = getMemories(userId).associateBy { it.id }
        val parent = all[parentMemoryId] ?: error("Memory parent does not exist: $parentMemoryId")

        require(all.containsKey(childMemoryId)) { "Memory does not exist: $childMemoryId" }

        // non-recursive
        require(!containsDescendant(all, childMemoryId, parentMemoryId)) {
            "Attaching the child would create a cycle: parent=$parentMemoryId child=$childMemoryId"
        }
        val existingContainer = all.values.firstOrNull {
            it.id != parentMemoryId && childMemoryId in it.childrenIds
        }
        require(existingContainer == null) {
            "Memory already belongs to another parent: $childMemoryId"
        }
        if (childMemoryId !in parent.childrenIds) {
            val children = (parent.childrenIds + childMemoryId).distinct()
            MemoryTable.update({ MemoryTable.id eq parentMemoryId }) {
                it[childrenIds] = children.encodeToString()
                it[updatedAt] = System.currentTimeMillis()
            }
        }
        // 이거 아님
        getMemories(userId).single()
    }

    private fun containsDescendant(
        memories: Map<Int, Memory>,
        startId: Int,
        targetId: Int,
        visited: MutableSet<Int> = mutableSetOf(),
    ): Boolean {
        if (!visited.add(startId)) return false
        val memory = memories[startId] ?: return false
        if (targetId in memory.childrenIds) return true
        return memory.childrenIds.any { containsDescendant(memories, it, targetId, visited) }
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
        )
    }

    private fun MemoryProposal.toMemory(userId: UserId, memoryId: Int): Memory {
        return Memory(
            id = memoryId,
            childrenIds = emptyList(),
            createdByUserId = userId.value,
            content = content,
            subject = subject,
            memoryType = memoryType,
            certainty = certainty,
            visibility = visibility,
            evidenceRefs = evidenceIds
        )
    }
}
