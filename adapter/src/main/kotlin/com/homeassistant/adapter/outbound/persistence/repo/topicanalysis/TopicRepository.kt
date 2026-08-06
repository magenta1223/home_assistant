package com.homeassistant.adapter.outbound.persistence.repo.topicanalysis

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.adapter.outbound.persistence.repo.indexing.enqueueIndex
import com.homeassistant.domain.identity.UserId
import com.homeassistant.application.topicanalysis.save.IndexTargetType
import com.homeassistant.application.topicanalysis.save.TopicCreator
import com.homeassistant.domain.memory.*
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.TopicProposal
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.source.SourceDescriptor
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/** Stores approved topic groups and canonical memories in normalized tables. */
internal class TopicRepository(private val db: Database) : TopicCreator {
    override fun create(
        proposal: TopicProposal,
        createdBy: UserId,
        source: SourceDescriptor,
    ): Topic = transaction(db) {
        val sourceType = source.type
        val sourceName = source.name
        require(proposal.memories.isNotEmpty()) { "topic must contain at least one memory" }
        findExistingTopic(proposal, sourceType, sourceName)?.let { return@transaction it }

        val now = System.currentTimeMillis()
        val topicId = TopicTable.insert {
            it[createdByUserId] = createdBy.value
            it[TopicTable.sourceType] = sourceType
            it[TopicTable.sourceName] = sourceName
            it[title] = proposal.title
            it[summary] = proposal.summary
            it[createdAt] = now
            it[updatedAt] = now
        }[TopicTable.id]

        proposal.categories.distinct().forEach { linkCategory(topicId, it) }
        proposal.memories
            .distinctBy { it.content to it.evidenceIds.toSet() }
            .forEach { memory -> insertMemory(topicId, createdBy.value, memory, now) }
        getTopic(topicId) ?: error("Created topic not found: $topicId")
    }

    private fun findExistingTopic(proposal: TopicProposal, sourceType: String, sourceName: String): Topic? {
        val evidence = proposal.evidenceIds.toSet()
        return TopicTable.selectAll()
            .where {
                (TopicTable.sourceType eq sourceType) and
                    (TopicTable.sourceName eq sourceName) and
                    (TopicTable.title eq proposal.title)
            }
            .mapNotNull { getTopic(it[TopicTable.id]) }
            .firstOrNull { topic -> topic.evidenceRefs.toSet() == evidence }
    }

    private fun insertMemory(
        topicId: Int,
        createdByUserId: String,
        proposal: MemoryProposal,
        now: Long,
    ) {
        val memoryId = MemoryTable.insert {
            it[MemoryTable.topicId] = topicId
            it[MemoryTable.createdByUserId] = createdByUserId
            it[content] = proposal.content
            it[subject] = proposal.subject
            it[memoryType] = proposal.memoryType.code
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
        enqueueIndex(IndexTargetType.MEMORY, memoryId)
    }

    private fun linkCategory(topicId: Int, rawCategory: String) {
        val category = rawCategory.trim().lowercase()
        if (category.isBlank()) return
        val categoryId = CategoryTable.selectAll()
            .where { CategoryTable.name eq category }
            .singleOrNull()
            ?.get(CategoryTable.id)
            ?: CategoryTable.insert { it[name] = category }[CategoryTable.id]
        TopicCategoryTable.insert {
            it[TopicCategoryTable.topicId] = topicId
            it[TopicCategoryTable.categoryId] = categoryId
        }
    }

    private fun getTopic(topicId: Int): Topic? {
        val row = TopicTable.selectAll().where { TopicTable.id eq topicId }.singleOrNull() ?: return null
        val memories = MemoryTable.selectAll()
            .where { MemoryTable.topicId eq topicId }
            .map { it.toMemory() }
        val categories = (TopicCategoryTable innerJoin CategoryTable)
            .select(CategoryTable.name)
            .where { TopicCategoryTable.topicId eq topicId }
            .map { it[CategoryTable.name] }
        return Topic(
            id = topicId,
            createdByUserId = row[TopicTable.createdByUserId],
            sourceType = row[TopicTable.sourceType],
            sourceName = row[TopicTable.sourceName],
            title = row[TopicTable.title],
            summary = row[TopicTable.summary],
            categories = categories,
            memories = memories,
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
