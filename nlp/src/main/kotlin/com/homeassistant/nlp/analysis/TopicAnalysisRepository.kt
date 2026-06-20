package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists source-agnostic topic candidates and their memory types/evidence. */
class TopicAnalysisRepository(private val db: Database) {
    private val json = Json
    fun createTopic(
        document: SourceDocument,
        title: String,
        summary: String,
        memoryTypes: List<MemoryType>,
        domains: List<String>,
        evidence: List<SourceRecord>,
        claims: List<NewTopicClaim>,
    ): TopicCandidate = transaction(db) {
        val existing = findExistingTopic(document, title, evidence)
        if (existing != null) return@transaction existing

        val now = System.currentTimeMillis()
        val topicId = TopicCandidateTable.insert {
            it[sourceType] = document.sourceType
            it[sourceName] = document.sourceName
            it[TopicCandidateTable.title] = title
            it[TopicCandidateTable.summary] = summary
            it[status] = CandidateStatus.PENDING.name
            it[createdAt] = now
            it[updatedAt] = now
        }[TopicCandidateTable.id]

        TopicClassificationTable.batchInsert(memoryTypes.distinct()) {
            this[TopicClassificationTable.topicId] = topicId
            this[TopicClassificationTable.memoryType] = it.code
        }
        TopicDomainTable.batchInsert(domains.distinct()) {
            this[TopicDomainTable.topicId] = topicId
            this[TopicDomainTable.domain] = it
        }
        TopicEvidenceTable.batchInsert(evidence.distinctBy { it.id }) {
            this[TopicEvidenceTable.topicId] = topicId
            this[TopicEvidenceTable.sourceRecordId] = it.id
            this[TopicEvidenceTable.sourceRecordRef] = it.ref
        }
        claims.distinctBy { it.text to it.evidence.map { record -> record.id }.toSet() }
            .forEach { claim ->
                val claimId = TopicClaimTable.insert {
                    it[TopicClaimTable.topicId] = topicId
                    it[TopicClaimTable.text] = claim.text
                    it[TopicClaimTable.subject] = claim.subject
                    it[TopicClaimTable.memoryType] = claim.memoryType.code
                    it[TopicClaimTable.certainty] = claim.certainty.name
                }[TopicClaimTable.id]
                TopicClaimEvidenceTable.batchInsert(claim.evidence.distinctBy { it.id }) {
                    this[TopicClaimEvidenceTable.claimId] = claimId
                    this[TopicClaimEvidenceTable.sourceRecordId] = it.id
                    this[TopicClaimEvidenceTable.sourceRecordRef] = it.ref
                }
            }

        getTopic(topicId)
    }

    private fun findExistingTopic(
        document: SourceDocument,
        title: String,
        evidence: List<SourceRecord>,
    ): TopicCandidate? {
        val evidenceIds = evidence.map { it.id }.toSet()
        val candidates = TopicCandidateTable.selectAll()
            .where {
                (TopicCandidateTable.sourceType eq document.sourceType) and
                    (TopicCandidateTable.sourceName eq document.sourceName) and
                    (TopicCandidateTable.title eq title)
            }
            .map { getTopic(it[TopicCandidateTable.id]) }
        return candidates.firstOrNull { candidate ->
            TopicEvidenceTable.selectAll()
                .where { TopicEvidenceTable.topicId eq candidate.id }
                .map { it[TopicEvidenceTable.sourceRecordId] }
                .toSet() == evidenceIds
        }
    }

    private fun getTopic(topicId: Int): TopicCandidate {
        val row = TopicCandidateTable.selectAll()
            .where { TopicCandidateTable.id eq topicId }
            .single()
        val memoryTypes = TopicClassificationTable.selectAll()
            .where { TopicClassificationTable.topicId eq topicId }
            .map {
                decodeMemoryType(it[TopicClassificationTable.memoryType])
            }
        val domains = TopicDomainTable.selectAll()
            .where { TopicDomainTable.topicId eq topicId }
            .map { it[TopicDomainTable.domain] }
        val evidence = TopicEvidenceTable.selectAll()
            .where { TopicEvidenceTable.topicId eq topicId }
            .map { it[TopicEvidenceTable.sourceRecordRef] }
        val claims = TopicClaimTable.selectAll()
            .where { TopicClaimTable.topicId eq topicId }
            .map { claimRow ->
                val claimId = claimRow[TopicClaimTable.id]
                TopicClaim(
                    id = claimId,
                    text = claimRow[TopicClaimTable.text],
                    subject = claimRow[TopicClaimTable.subject],
                    memoryType = decodeMemoryType(claimRow[TopicClaimTable.memoryType]),
                    certainty = ClaimCertainty.valueOf(claimRow[TopicClaimTable.certainty]),
                    evidenceRefs = TopicClaimEvidenceTable.selectAll()
                        .where { TopicClaimEvidenceTable.claimId eq claimId }
                        .map { it[TopicClaimEvidenceTable.sourceRecordRef] },
                )
            }

        return TopicCandidate(
            id = topicId,
            sourceType = row[TopicCandidateTable.sourceType],
            sourceName = row[TopicCandidateTable.sourceName],
            title = row[TopicCandidateTable.title],
            summary = row[TopicCandidateTable.summary],
            memoryTypes = memoryTypes,
            domains = domains,
            evidenceRefs = evidence,
            claims = claims,
            status = CandidateStatus.valueOf(row[TopicCandidateTable.status]),
        )
    }

    private fun decodeMemoryType(value: String): MemoryType =
        json.decodeFromString<MemoryType>(json.encodeToString(value))
}
/**
 * New claim payload ready to be persisted under a topic candidate.
 *
 * @property text Claim text suitable for memory review.
 * @property subject Person, place, or concept the claim is about.
 * @property memoryType Memory category assigned to the claim.
 * @property certainty How directly source evidence supports the claim.
 * @property evidence Source records that support the claim.
 */
data class NewTopicClaim(
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: ClaimCertainty,
    val evidence: List<SourceRecord>,
)
