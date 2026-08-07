package com.homeassistant.application.topicanalysis.review

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.topicanalysis.TopicProposal

data class TopicAnalysisReview(
    val id: String,
    val requestedBy: UserId,
    val source: SourceDescriptor,
    val proposals: List<TopicProposal>,
)

/** Stores and retrieves topic-analysis previews awaiting user review. */
interface TopicAnalysisReviewStore {
    /** Persists a topic-analysis review for later retrieval. */
    fun create(
        requestedBy: UserId,
        source: SourceDescriptor,
        proposals: List<TopicProposal>,
    ): TopicAnalysisReview

    /** Finds a topic-analysis review by its identifier, if it exists. */
    fun find(reviewId: String): TopicAnalysisReview?
}

class TopicAnalysisReviewNotFoundException(
    val reviewId: String,
) : RuntimeException("Topic analysis review not found: $reviewId")
