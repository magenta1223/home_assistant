package com.homeassistant.repository.repo.topicanalysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.utils.JsonSerializer.decodeFromString
import com.homeassistant.core.utils.JsonSerializer.encodeToString
import com.homeassistant.datamodel.topicanalysis.ClaimCertainty
import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import com.homeassistant.datamodel.topicanalysis.TopicClaim
import com.homeassistant.datamodel.topicanalysis.TopicClaimCandidate
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.repository.repo.indexing.enqueueIndex
import com.homeassistant.repository.db.tables.TopicCandidateTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Persists source-agnostic topic candidates and their memory types/evidence. */
internal class TopicAnalysisRepository(private val db: Database) : TopicAnalysisStore {

    override fun createTopic(candidate: TopicCandidate): Topic = transaction(db) {
        val existing = findExistingTopic(candidate)
        if (existing != null) return@transaction approveTopic(existing.id)

        val now = System.currentTimeMillis()
        val distinctEvidenceRefs = candidate.evidenceRefs.distinct()
        val distinctClaims = candidate.claims.distinctBy { it.text to it.evidenceRefs.toSet() }
        val topicId = TopicCandidateTable.insert {
            it[sourceType] = candidate.sourceType
            it[sourceName] = candidate.sourceName
            it[TopicCandidateTable.title] = candidate.title
            it[TopicCandidateTable.summary] = candidate.summary
            it[status] = CandidateStatus.APPROVED.name
            it[memoryTypesJson] = candidate.memoryTypes.distinct().encodeToString()
            it[domainsJson] = candidate.domains.distinct().encodeToString()
            it[evidenceJson] = distinctEvidenceRefs.encodeToString()
            it[claimsJson] = distinctClaims.toPersistedClaims().encodeToString()
            it[createdAt] = now
            it[updatedAt] = now
        }[TopicCandidateTable.id]
        enqueueIndex(IndexTargetType.TOPIC, topicId)

        getTopic(topicId)
    }

    override fun searchApprovedTopics(query: String, limit: Int): List<Topic> = transaction(db) {
        val boundedLimit = limit.coerceIn(1, 10)
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return@transaction emptyList()

        TopicCandidateTable.selectAll()
            .where { TopicCandidateTable.status eq CandidateStatus.APPROVED.name }
            .map { row -> getTopic(row[TopicCandidateTable.id]) }
            .mapNotNull { topic ->
                val score = scoreTopic(topic, queryTokens)
                if (score <= 0) null else ScoredTopic(topic, score)
            }
            .sortedWith(compareByDescending<ScoredTopic> { it.score }.thenBy { it.topic.id })
            .take(boundedLimit)
            .map { it.topic }
    }

    override fun getApprovedTopics(topicIds: Collection<Int>): List<Topic> = transaction(db) {
        val distinctIds = topicIds.distinct()
        if (distinctIds.isEmpty()) return@transaction emptyList()

        TopicCandidateTable.selectAll()
            .where {
                (TopicCandidateTable.id inList distinctIds) and
                    (TopicCandidateTable.status eq CandidateStatus.APPROVED.name)
            }
            .map { row -> getTopic(row[TopicCandidateTable.id]) }
    }

    private fun approveTopic(topicId: Int): Topic {
        TopicCandidateTable.update({ TopicCandidateTable.id eq topicId }) {
            it[status] = CandidateStatus.APPROVED.name
            it[updatedAt] = System.currentTimeMillis()
        }
        enqueueIndex(IndexTargetType.TOPIC, topicId)
        return getTopic(topicId)
    }

    private fun tokenize(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 }
            .toSet()

    private fun scoreTopic(topic: Topic, queryTokens: Set<String>): Int {
        val title = topic.title.lowercase()
        val summary = topic.summary.lowercase()
        val claims = topic.claims.joinToString(" ") { it.text }.lowercase()
        return queryTokens.sumOf { token ->
            var score = 0
            if (title.contains(token)) score += 4
            if (summary.contains(token)) score += 2
            if (claims.contains(token)) score += 3
            score
        }
    }

    private fun findExistingTopic(candidate: TopicCandidate): Topic? {
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

    private fun getTopic(topicId: Int): Topic {
        val row = TopicCandidateTable.selectAll()
            .where { TopicCandidateTable.id eq topicId }
            .single()

        return Topic(
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

    private fun List<TopicClaimCandidate>.toPersistedClaims(): List<PersistedTopicClaim> =
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

private data class ScoredTopic(val topic: Topic, val score: Int)

@Serializable
private data class PersistedTopicClaim(
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: ClaimCertainty,
    val evidenceRefs: List<Int>,
)
