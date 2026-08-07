package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryEvidenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicTable
import com.homeassistant.application.memory.MemoryContext
import com.homeassistant.application.memory.MemoryTopicContext
import com.homeassistant.application.memory.io.MemoryReader
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDescriptor
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class MemoryRepository(
    private val db: Database,
) : MemoryReader {
    override fun findByIds(memoryIds: Collection<Int>): List<MemoryContext> = transaction(db) {
        val ids = memoryIds.distinct()
        if (ids.isEmpty()) return@transaction emptyList()
        MemoryTable.selectAll()
            .where { MemoryTable.id inList ids }
            .map { row ->
                val memory = row.toMemory()
                MemoryContext(memory, memory.topicId?.let(::topicContext))
            }
    }

    override fun findVisibleByIds(userId: UserId, memoryIds: Collection<Int>): List<MemoryContext> =
        findByIds(memoryIds).filter { it.memory.isVisibleTo(userId) }

    private fun topicContext(topicId: Int): MemoryTopicContext? =
        TopicTable.selectAll()
            .where { TopicTable.id eq topicId }
            .singleOrNull()
            ?.let { row ->
                MemoryTopicContext(
                    id = topicId,
                    title = row[TopicTable.title],
                    summary = row[TopicTable.summary],
                    source = SourceDescriptor(row[TopicTable.sourceType], row[TopicTable.sourceName]),
                )
            }

    private fun ResultRow.toMemory(): Memory {
        val memoryId = this[MemoryTable.id]
        val evidenceRefs = MemoryEvidenceTable.select(MemoryEvidenceTable.sourceRecordId)
            .where { MemoryEvidenceTable.memoryId eq memoryId }
            .map { it[MemoryEvidenceTable.sourceRecordId] }
        return Memory(
            id = memoryId,
            topicId = this[MemoryTable.topicId],
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
