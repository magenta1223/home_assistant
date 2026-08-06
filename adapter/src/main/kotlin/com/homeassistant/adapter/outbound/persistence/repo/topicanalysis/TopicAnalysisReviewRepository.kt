package com.homeassistant.adapter.outbound.persistence.repo.topicanalysis

import com.homeassistant.adapter.outbound.persistence.db.tables.TopicAnalysisReviewTable
import com.homeassistant.adapter.shared.json.JsonSerializer.decodeFromString
import com.homeassistant.adapter.shared.json.JsonSerializer.encodeToString
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReview
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReviewStore
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.topicanalysis.TopicProposal
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** Persists topic proposals so saving does not call the LLM again. */
internal class TopicAnalysisReviewRepository(private val db: Database) : TopicAnalysisReviewStore {
    override fun create(
        requestedBy: UserId,
        source: SourceDescriptor,
        proposals: List<TopicProposal>,
    ): TopicAnalysisReview = transaction(db) {
        val reviewId = UUID.randomUUID().toString()
        TopicAnalysisReviewTable.insert {
            it[TopicAnalysisReviewTable.reviewId] = reviewId
            it[requestedByUserId] = requestedBy.value
            it[sourceType] = source.type
            it[sourceName] = source.name
            it[proposalsJson] = proposals.encodeToString()
            it[createdAt] = System.currentTimeMillis()
        }
        TopicAnalysisReview(reviewId, requestedBy, source, proposals)
    }

    override fun find(reviewId: String): TopicAnalysisReview? = transaction(db) {
        TopicAnalysisReviewTable.selectAll()
            .where { TopicAnalysisReviewTable.reviewId eq reviewId }
            .singleOrNull()
            ?.toReview()
    }

    private fun ResultRow.toReview(): TopicAnalysisReview =
        TopicAnalysisReview(
            id = this[TopicAnalysisReviewTable.reviewId],
            requestedBy = UserId(this[TopicAnalysisReviewTable.requestedByUserId]),
            source = SourceDescriptor(
                type = this[TopicAnalysisReviewTable.sourceType],
                name = this[TopicAnalysisReviewTable.sourceName],
            ),
            proposals = this[TopicAnalysisReviewTable.proposalsJson].decodeFromString(),
        )
}
