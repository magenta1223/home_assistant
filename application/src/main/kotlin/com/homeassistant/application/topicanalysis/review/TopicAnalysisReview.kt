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

interface TopicAnalysisReviewStore {
    fun create(
        requestedBy: UserId,
        source: SourceDescriptor,
        proposals: List<TopicProposal>,
    ): TopicAnalysisReview

    fun find(reviewId: String): TopicAnalysisReview?
}

class TopicAnalysisReviewNotFoundException(
    val reviewId: String,
) : RuntimeException("Topic analysis review not found: $reviewId")
