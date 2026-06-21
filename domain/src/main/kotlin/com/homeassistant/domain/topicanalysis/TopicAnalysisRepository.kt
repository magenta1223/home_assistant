package com.homeassistant.domain.topicanalysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.domain.db.tables.TopicCandidateTable
import com.homeassistant.nlp.topicanalysis.ClaimCertainty
import com.homeassistant.nlp.topicanalysis.NewTopicClaim
import com.homeassistant.nlp.topicanalysis.SourceDocument
import com.homeassistant.nlp.topicanalysis.SourceRecord
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
        val distinctEvidence = evidence.distinctBy { it.id }
        val distinctClaims = claims.distinctBy { it.text to it.evidence.map { record -> record.id }.toSet() }
        val topicId = TopicCandidateTable.insert {
            it[sourceType] = document.sourceType
            it[sourceName] = document.sourceName
            it[TopicCandidateTable.title] = title
            it[TopicCandidateTable.summary] = summary
            it[status] = CandidateStatus.PENDING.name
            it[memoryTypesJson] = json.encodeToString(memoryTypes.distinct())
            it[domainsJson] = json.encodeToString(domains.distinct())
            it[evidenceJson] = json.encodeToString(distinctEvidence.toPersistedEvidence())
            it[claimsJson] = json.encodeToString(distinctClaims.toPersistedClaims())
            it[createdAt] = now
            it[updatedAt] = now
        }[TopicCandidateTable.id]

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

    private fun List<SourceRecord>.toPersistedEvidence(): List<PersistedEvidence> =
        map { PersistedEvidence(id = it.id, ref = it.ref) }

    private fun List<NewTopicClaim>.toPersistedClaims(): List<PersistedTopicClaim> =
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
