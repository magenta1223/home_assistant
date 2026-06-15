package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryClassification
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Persists source-agnostic topic candidates and their classifications/evidence. */
class TopicAnalysisRepository(private val db: Database) {
    fun createTopic(
        document: SourceDocument,
        title: TopicTitle,
        summary: TopicSummary,
        classifications: List<MemoryClassification>,
        domains: List<DomainTag>,
        evidence: List<SourceRecord>,
        claims: List<NewTopicClaim>,
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

        TopicClassificationTable.batchInsert(classifications.distinct()) {
            this[TopicClassificationTable.topicId] = topicId
            this[TopicClassificationTable.memoryKind] = it.kind.name
            this[TopicClassificationTable.memorySubtype] = it.subtypeCode
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
        claims.distinctBy { it.text.value to it.evidence.map { record -> record.id.value }.toSet() }
            .forEach { claim ->
                val claimId = TopicClaimTable.insert {
                    it[TopicClaimTable.topicId] = topicId
                    it[TopicClaimTable.text] = claim.text.value
                    it[TopicClaimTable.subject] = claim.subject.value
                    it[TopicClaimTable.memoryKind] = claim.classification.kind.name
                    it[TopicClaimTable.memorySubtype] = claim.classification.subtypeCode
                    it[TopicClaimTable.certainty] = claim.certainty.name
                }[TopicClaimTable.id]
                TopicClaimEvidenceTable.batchInsert(claim.evidence.distinctBy { it.id }) {
                    this[TopicClaimEvidenceTable.claimId] = claimId
                    this[TopicClaimEvidenceTable.sourceRecordId] = it.id.value
                    this[TopicClaimEvidenceTable.sourceRecordRef] = it.ref.value
                }
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
        val classifications = TopicClassificationTable.selectAll()
            .where { TopicClassificationTable.topicId eq topicId }
            .map {
                MemoryClassification.parse(
                    it[TopicClassificationTable.memoryKind],
                    it[TopicClassificationTable.memorySubtype],
                )
            }
        val domains = TopicDomainTable.selectAll()
            .where { TopicDomainTable.topicId eq topicId }
            .map { DomainTag(it[TopicDomainTable.domain]) }
        val evidence = TopicEvidenceTable.selectAll()
            .where { TopicEvidenceTable.topicId eq topicId }
            .map { SourceRecordRef(it[TopicEvidenceTable.sourceRecordRef]) }
        val claims = TopicClaimTable.selectAll()
            .where { TopicClaimTable.topicId eq topicId }
            .map { claimRow ->
                val claimId = claimRow[TopicClaimTable.id]
                TopicClaim(
                    id = TopicClaimId(claimId),
                    text = ClaimText(claimRow[TopicClaimTable.text]),
                    subject = ClaimSubject(claimRow[TopicClaimTable.subject]),
                    classification = MemoryClassification.parse(
                        claimRow[TopicClaimTable.memoryKind],
                        claimRow[TopicClaimTable.memorySubtype],
                    ),
                    certainty = ClaimCertainty.valueOf(claimRow[TopicClaimTable.certainty]),
                    evidenceRefs = TopicClaimEvidenceTable.selectAll()
                        .where { TopicClaimEvidenceTable.claimId eq claimId }
                        .map { SourceRecordRef(it[TopicClaimEvidenceTable.sourceRecordRef]) },
                )
            }

        return TopicCandidate(
            id = TopicCandidateId(topicId),
            sourceType = SourceType(row[TopicCandidateTable.sourceType]),
            sourceName = SourceName(row[TopicCandidateTable.sourceName]),
            title = TopicTitle(row[TopicCandidateTable.title]),
            summary = TopicSummary(row[TopicCandidateTable.summary]),
            classifications = classifications,
            domains = domains,
            evidenceRefs = evidence,
            claims = claims,
            status = CandidateStatus.valueOf(row[TopicCandidateTable.status]),
        )
    }
}

data class NewTopicClaim(
    val text: ClaimText,
    val subject: ClaimSubject,
    val classification: MemoryClassification,
    val certainty: ClaimCertainty,
    val evidence: List<SourceRecord>,
)
