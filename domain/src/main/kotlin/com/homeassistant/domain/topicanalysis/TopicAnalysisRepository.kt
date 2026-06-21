package com.homeassistant.domain.topicanalysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.utils.JsonSerializer.decodeFromString
import com.homeassistant.core.utils.JsonSerializer.encodeToString
import com.homeassistant.domain.db.tables.TopicCandidateTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/** Persists source-agnostic topic candidates and their memory types/evidence. */
class TopicAnalysisRepository(private val db: Database) {

    fun createTopic(candidate: NewTopicCandidate): TopicCandidate = transaction(db) {
        val existing = findExistingTopic(candidate)
        if (existing != null) return@transaction existing

        val now = System.currentTimeMillis()
        val distinctEvidenceRefs = candidate.evidenceRefs.distinct()
        val distinctClaims = candidate.claims.distinctBy { it.text to it.evidenceRefs.toSet() }
        val topicId = TopicCandidateTable.insert {
            it[sourceType] = candidate.sourceType
            it[sourceName] = candidate.sourceName
            it[TopicCandidateTable.title] = candidate.title
            it[TopicCandidateTable.summary] = candidate.summary
            it[status] = CandidateStatus.PENDING.name
            it[memoryTypesJson] = candidate.memoryTypes.distinct().encodeToString()
            it[domainsJson] = candidate.domains.distinct().encodeToString()
            it[evidenceJson] = distinctEvidenceRefs.encodeToString()
            it[claimsJson] = distinctClaims.toPersistedClaims().encodeToString()
            it[createdAt] = now
            it[updatedAt] = now
        }[TopicCandidateTable.id]

        getTopic(topicId)
    }

    private fun findExistingTopic(candidate: NewTopicCandidate): TopicCandidate? {
        val evidenceRefs = candidate.evidenceRefs.toSet()
        val candidates = TopicCandidateTable.selectAll()
            .where {
                (TopicCandidateTable.sourceType eq candidate.sourceType) and
                    (TopicCandidateTable.sourceName eq candidate.sourceName) and
                    (TopicCandidateTable.title eq candidate.title)
            }
        return candidates.firstOrNull { row ->
            row[TopicCandidateTable.evidenceJson]
                .decodeFromString<List<Int>>()
                .toSet() == evidenceRefs
        }?.let { getTopic(it[TopicCandidateTable.id]) }
    }

    private fun getTopic(topicId: Int): TopicCandidate {
        val row = TopicCandidateTable.selectAll()
            .where { TopicCandidateTable.id eq topicId }
            .single()

        return TopicCandidate(
            id = topicId,
            sourceType = row[TopicCandidateTable.sourceType],
            sourceName = row[TopicCandidateTable.sourceName],
            title = row[TopicCandidateTable.title],
            summary = row[TopicCandidateTable.summary],
            memoryTypes = row[TopicCandidateTable.memoryTypesJson].decodeFromString(),
            domains = row[TopicCandidateTable.domainsJson].decodeFromString(),
            evidenceRefs = row[TopicCandidateTable.evidenceJson].decodeFromString(),
            claims = decodeClaims(row),
            status = CandidateStatus.valueOf(row[TopicCandidateTable.status]),
        )
    }

    private fun decodeClaims(row: ResultRow): List<TopicClaim> =
        row[TopicCandidateTable.claimsJson]
            .decodeFromString<List<PersistedTopicClaim>>()
            .mapIndexed { index, claim ->
                TopicClaim(
                    id = index + 1,
                    text = claim.text,
                    subject = claim.subject,
                    memoryType = claim.memoryType,
                    certainty = claim.certainty,
                    evidenceRefs = claim.evidenceRefs,
                )
            }



    private fun List<NewTopicCandidateClaim>.toPersistedClaims(): List<PersistedTopicClaim> =
        map { claim ->
            PersistedTopicClaim(
                text = claim.text,
                subject = claim.subject,
                memoryType = claim.memoryType,
                certainty = claim.certainty,
                evidenceRefs = claim.evidenceRefs.distinct(),
            )
        }
}

@Serializable
private data class PersistedTopicClaim(
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: ClaimCertainty,
    val evidenceRefs: List<Int>,
)
