package com.homeassistant.adapter.outbound.persistence.repo.topicanalysis

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.adapter.outbound.persistence.repo.indexing.enqueueIndex
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.domain.memory.*
import com.homeassistant.domain.topicanalysis.ProposedMemory
import com.homeassistant.domain.topicanalysis.ProposedTopic
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/** Stores approved topic groups and canonical memories in normalized tables. */
internal class TopicAnalysisRepository(private val db: Database) : TopicAnalysisStore {
    override fun createTopic(proposal: ProposedTopic): Topic = transaction(db) {
        require(proposal.createdByUserId.isNotBlank()) { "createdByUserId is required" }
        require(proposal.memories.isNotEmpty()) { "topic must contain at least one memory" }
        findExistingTopic(proposal)?.let { return@transaction it }

        val now = System.currentTimeMillis()
        val topicId = TopicTable.insert {
            it[createdByUserId] = proposal.createdByUserId
            it[sourceType] = proposal.sourceType
            it[sourceName] = proposal.sourceName
            it[title] = proposal.title
            it[summary] = proposal.summary
            it[createdAt] = now
            it[updatedAt] = now
        }[TopicTable.id]

        proposal.categories.distinct().forEach { linkCategory(topicId, it) }
        proposal.memories
            .distinctBy { it.text to it.evidenceRefs.toSet() }
            .forEach { memory -> insertMemory(topicId, proposal.createdByUserId, memory, now) }
        getTopic(topicId) ?: error("Created topic not found: $topicId")
    }

    override fun searchApprovedTopics(userId: UserId, query: String, limit: Int): List<Topic> = transaction(db) {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return@transaction emptyList()
        TopicTable.selectAll()
            .mapNotNull { getTopic(it[TopicTable.id], userId) }
            .mapNotNull { topic -> scoreTopic(topic, tokens).takeIf { it > 0 }?.let { ScoredTopic(topic, it) } }
            .sortedWith(compareByDescending<ScoredTopic> { it.score }.thenBy { it.topic.id })
            .take(limit.coerceIn(1, 10))
            .map { it.topic }
    }

    override fun getApprovedTopics(userId: UserId, topicIds: Collection<Int>): List<Topic> = transaction(db) {
        topicIds.distinct().mapNotNull { getTopic(it, userId) }
    }

    override fun getApprovedTopicsForIndexing(topicIds: Collection<Int>): List<Topic> = transaction(db) {
        topicIds.distinct().mapNotNull(::getTopic)
    }

    private fun findExistingTopic(proposal: ProposedTopic): Topic? {
        val evidence = proposal.memories.flatMap { it.evidenceRefs }.toSet()
        return TopicTable.selectAll()
            .where {
                (TopicTable.sourceType eq proposal.sourceType) and
                    (TopicTable.sourceName eq proposal.sourceName) and
                    (TopicTable.title eq proposal.title)
            }
            .mapNotNull { getTopic(it[TopicTable.id]) }
            .firstOrNull { topic -> topic.evidenceRefs.toSet() == evidence }
    }

    private fun insertMemory(
        topicId: Int,
        createdByUserId: String,
        proposal: ProposedMemory,
        now: Long,
    ) {
        val memoryId = MemoryTable.insert {
            it[MemoryTable.topicId] = topicId
            it[MemoryTable.createdByUserId] = createdByUserId
            it[content] = proposal.text
            it[subject] = proposal.subject
            it[memoryType] = proposal.memoryType.code
            it[certainty] = proposal.certainty.name
            it[visibility] = proposal.visibility.name
            it[createdAt] = now
            it[updatedAt] = now
        }[MemoryTable.id]
        proposal.evidenceRefs.distinct().forEach { sourceRecordId ->
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

    private fun getTopic(topicId: Int, requester: UserId? = null): Topic? {
        val row = TopicTable.selectAll().where { TopicTable.id eq topicId }.singleOrNull() ?: return null
        val memories = MemoryTable.selectAll()
            .where { MemoryTable.topicId eq topicId }
            .map { it.toMemory() }
            .filter { requester == null || it.isVisibleTo(requester) }
        if (requester != null && memories.isEmpty()) return null
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
            status = CandidateStatus.APPROVED,
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

    private fun tokenize(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 }
            .toSet()

    private fun scoreTopic(topic: Topic, tokens: Set<String>): Int {
        val title = topic.title.lowercase()
        val summary = topic.summary.lowercase()
        val memories = topic.memories.joinToString(" ") { it.content }.lowercase()
        return tokens.sumOf { token ->
            (if (title.contains(token)) 4 else 0) +
                (if (summary.contains(token)) 2 else 0) +
                (if (memories.contains(token)) 3 else 0)
        }
    }
}

private data class ScoredTopic(val topic: Topic, val score: Int)
