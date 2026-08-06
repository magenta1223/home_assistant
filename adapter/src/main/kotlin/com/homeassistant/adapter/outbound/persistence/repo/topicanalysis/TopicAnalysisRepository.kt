package com.homeassistant.adapter.outbound.persistence.repo.topicanalysis

import com.homeassistant.domain.memory.CandidateStatus
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.shared.json.JsonSerializer.decodeFromString
import com.homeassistant.adapter.shared.json.JsonSerializer.encodeToString
import com.homeassistant.domain.topicanalysis.ClaimCertainty
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.ProposedTopic
import com.homeassistant.domain.topicanalysis.TopicClaim
import com.homeassistant.domain.topicanalysis.ProposedMemory
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.adapter.outbound.persistence.repo.indexing.enqueueIndex
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicCandidateTable
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

    override fun createTopic(proposal: ProposedTopic): Topic = transaction(db) {
        require(proposal.createdByUserId.isNotBlank()) { "createdByUserId is required" }
        val existing = findExistingTopic(proposal)
        if (existing != null) return@transaction approveTopic(existing.id)

        val now = System.currentTimeMillis()
        val distinctEvidenceRefs = proposal.evidenceRefs.distinct()
        val distinctMemories = proposal.memories.distinctBy { it.text to it.evidenceRefs.toSet() }
        val topicId = TopicCandidateTable.insert {
            it[familyId] = LEGACY_HOUSEHOLD_ID
            it[createdByUserId] = proposal.createdByUserId
            it[sourceType] = proposal.sourceType
            it[sourceName] = proposal.sourceName
            it[TopicCandidateTable.title] = proposal.title
            it[TopicCandidateTable.summary] = proposal.summary
            it[status] = CandidateStatus.APPROVED.name
            it[memoryTypesJson] = proposal.memoryTypes.distinct().encodeToString()
            it[domainsJson] = proposal.categories.distinct().encodeToString()
            it[evidenceJson] = distinctEvidenceRefs.encodeToString()
            it[claimsJson] = distinctMemories.toPersistedClaims().encodeToString()
            it[createdAt] = now
            it[updatedAt] = now
        }[TopicCandidateTable.id]
        enqueueIndex(IndexTargetType.TOPIC, topicId)

        getTopic(topicId)
    }

    override fun searchApprovedTopics(
        userId: UserId,
        query: String,
        limit: Int,
    ): List<Topic> = transaction(db) {
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

    override fun getApprovedTopics(
        userId: UserId,
        topicIds: Collection<Int>,
    ): List<Topic> = transaction(db) {
        val distinctIds = topicIds.distinct()
        if (distinctIds.isEmpty()) return@transaction emptyList()

        TopicCandidateTable.selectAll()
            .where {
                (TopicCandidateTable.id inList distinctIds) and
                    (TopicCandidateTable.status eq CandidateStatus.APPROVED.name)
            }
            .map { row -> getTopic(row[TopicCandidateTable.id]) }
    }

    override fun getApprovedTopicsForIndexing(topicIds: Collection<Int>): List<Topic> = transaction(db) {
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

    private fun findExistingTopic(proposal: ProposedTopic): Topic? {
        val evidenceRefs = proposal.evidenceRefs.toSet()
        val candidates = TopicCandidateTable.selectAll()
            .where {
                (TopicCandidateTable.sourceType eq proposal.sourceType) and
                    (TopicCandidateTable.sourceName eq proposal.sourceName) and
                    (TopicCandidateTable.title eq proposal.title)
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
            createdByUserId = row[TopicCandidateTable.createdByUserId],
            sourceType = row[TopicCandidateTable.sourceType],
            sourceName = row[TopicCandidateTable.sourceName],
            title = row[TopicCandidateTable.title],
            summary = row[TopicCandidateTable.summary],
            memoryTypes = row[TopicCandidateTable.memoryTypesJson].decodeFromString(),
            categories = row[TopicCandidateTable.domainsJson].decodeFromString(),
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

    private fun List<ProposedMemory>.toPersistedClaims(): List<PersistedTopicClaim> =
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

private const val LEGACY_HOUSEHOLD_ID = "household"
