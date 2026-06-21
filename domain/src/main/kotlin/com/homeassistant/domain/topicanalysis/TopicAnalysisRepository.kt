package com.homeassistant.domain.topicanalysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.domain.db.tables.TopicCandidateTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Persists source-agnostic topic candidates and their memory types/evidence. */
class TopicAnalysisRepository(private val db: Database) {
    private val json = Json

    fun createTopic(candidate: NewTopicCandidate): TopicCandidate = transaction(db) {
        val existing = findExistingTopic(candidate)
        if (existing != null) return@transaction existing

        val now = System.currentTimeMillis()
        val distinctEvidence = candidate.evidence.distinctBy { it.id }
        val distinctClaims = candidate.claims.distinctBy { it.text to it.evidence.map { evidence -> evidence.id }.toSet() }
        val topicId = TopicCandidateTable.insert {
            it[sourceType] = candidate.sourceType
            it[sourceName] = candidate.sourceName
            it[TopicCandidateTable.title] = candidate.title
            it[TopicCandidateTable.summary] = candidate.summary
            it[status] = CandidateStatus.PENDING.name
            it[memoryTypesJson] = json.encodeToString(candidate.memoryTypes.distinct())
            it[domainsJson] = json.encodeToString(candidate.domains.distinct())
            it[evidenceJson] = json.encodeToString(distinctEvidence.toPersistedEvidence())
            it[claimsJson] = json.encodeToString(distinctClaims.toPersistedClaims())
            it[createdAt] = now
            it[updatedAt] = now
        }[TopicCandidateTable.id]

        getTopic(topicId)
    }

    private fun findExistingTopic(candidate: NewTopicCandidate): TopicCandidate? {
        val evidenceIds = candidate.evidence.map { it.id }.toSet()
        val candidates = TopicCandidateTable.selectAll()
            .where {
                (TopicCandidateTable.sourceType eq candidate.sourceType) and
                    (TopicCandidateTable.sourceName eq candidate.sourceName) and
                    (TopicCandidateTable.title eq candidate.title)
            }
        return candidates.firstOrNull { row ->
            decodeEvidence(row[TopicCandidateTable.evidenceJson]).map { it.id }.toSet() == evidenceIds
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
            memoryTypes = json.decodeFromString(row[TopicCandidateTable.memoryTypesJson]),
            domains = json.decodeFromString(row[TopicCandidateTable.domainsJson]),
            evidenceRefs = decodeEvidence(row[TopicCandidateTable.evidenceJson]).map { it.ref },
            claims = decodeClaims(row),
            status = CandidateStatus.valueOf(row[TopicCandidateTable.status]),
        )
    }

    private fun decodeClaims(row: ResultRow): List<TopicClaim> =
        json.decodeFromString<List<PersistedTopicClaim>>(row[TopicCandidateTable.claimsJson])
            .mapIndexed { index, claim ->
                TopicClaim(
                    id = index + 1,
                    text = claim.text,
                    subject = claim.subject,
                    memoryType = claim.memoryType,
                    certainty = claim.certainty,
                    evidenceRefs = claim.evidence.map { it.ref },
                )
            }

    private fun decodeEvidence(value: String): List<PersistedEvidence> =
        json.decodeFromString(value)

    private fun List<NewTopicCandidateEvidence>.toPersistedEvidence(): List<PersistedEvidence> =
        map { PersistedEvidence(id = it.id, ref = it.ref) }

    private fun List<NewTopicCandidateClaim>.toPersistedClaims(): List<PersistedTopicClaim> =
        map { claim ->
            PersistedTopicClaim(
                text = claim.text,
                subject = claim.subject,
                memoryType = claim.memoryType,
                certainty = claim.certainty,
                evidence = claim.evidence.distinctBy { it.id }.toPersistedEvidence(),
            )
        }
}

@Serializable
private data class PersistedEvidence(
    val id: String,
    val ref: Int,
)

@Serializable
private data class PersistedTopicClaim(
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: ClaimCertainty,
    val evidence: List<PersistedEvidence>,
)
