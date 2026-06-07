package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class TopicAnalysisRepository(private val db: Database) {
    fun createTopic(
        document: SourceDocument,
        title: TopicTitle,
        summary: TopicSummary,
        memoryTypes: List<MemoryType>,
        domains: List<DomainTag>,
        evidence: List<SourceRecord>,
    ): TopicCandidate = transaction(db) {
        val existing = findExistingTopic(document, title, evidence)
        if (existing != null) return@transaction existing

        val now = System.currentTimeMillis()
        val topicId = TopicCandidateTable.insert {
            it[sourceType] = document.sourceType.value
            it[sourceName] = document.sourceName.value
            it[TopicCandidateTable.title] = title.value
            it[TopicCandidateTable.summary] = summary.value
            it[status] = CandidateStatus.PENDING.name
            it[createdAt] = now
            it[updatedAt] = now
        }[TopicCandidateTable.id]

        TopicMemoryTypeTable.batchInsert(memoryTypes.distinct()) {
            this[TopicMemoryTypeTable.topicId] = topicId
            this[TopicMemoryTypeTable.memoryType] = it.name
        }
        TopicDomainTable.batchInsert(domains.distinct()) {
            this[TopicDomainTable.topicId] = topicId
            this[TopicDomainTable.domain] = it.value
        }
        TopicEvidenceTable.batchInsert(evidence.distinctBy { it.id }) {
            this[TopicEvidenceTable.topicId] = topicId
            this[TopicEvidenceTable.sourceRecordId] = it.id.value
            this[TopicEvidenceTable.sourceRecordRef] = it.ref.value
        }

        getTopic(topicId)
    }

    private fun findExistingTopic(
        document: SourceDocument,
        title: TopicTitle,
        evidence: List<SourceRecord>,
    ): TopicCandidate? {
        val evidenceIds = evidence.map { it.id.value }.toSet()
        val candidates = TopicCandidateTable.selectAll()
            .where {
                (TopicCandidateTable.sourceType eq document.sourceType.value) and
                    (TopicCandidateTable.sourceName eq document.sourceName.value) and
                    (TopicCandidateTable.title eq title.value)
            }
            .map { getTopic(it[TopicCandidateTable.id]) }
        return candidates.firstOrNull { candidate ->
            TopicEvidenceTable.selectAll()
                .where { TopicEvidenceTable.topicId eq candidate.id.value }
                .map { it[TopicEvidenceTable.sourceRecordId] }
                .toSet() == evidenceIds
        }
    }

    private fun getTopic(topicId: Int): TopicCandidate {
        val row = TopicCandidateTable.selectAll()
            .where { TopicCandidateTable.id eq topicId }
            .single()
        val memoryTypes = TopicMemoryTypeTable.selectAll()
            .where { TopicMemoryTypeTable.topicId eq topicId }
            .map { MemoryType.valueOf(it[TopicMemoryTypeTable.memoryType]) }
        val domains = TopicDomainTable.selectAll()
            .where { TopicDomainTable.topicId eq topicId }
            .map { DomainTag(it[TopicDomainTable.domain]) }
        val evidence = TopicEvidenceTable.selectAll()
            .where { TopicEvidenceTable.topicId eq topicId }
            .map { SourceRecordRef(it[TopicEvidenceTable.sourceRecordRef]) }

        return TopicCandidate(
            id = TopicCandidateId(topicId),
            sourceType = SourceType(row[TopicCandidateTable.sourceType]),
            sourceName = SourceName(row[TopicCandidateTable.sourceName]),
            title = TopicTitle(row[TopicCandidateTable.title]),
            summary = TopicSummary(row[TopicCandidateTable.summary]),
            memoryTypes = memoryTypes,
            domains = domains,
            evidenceRefs = evidence,
            status = CandidateStatus.valueOf(row[TopicCandidateTable.status]),
        )
    }
}
