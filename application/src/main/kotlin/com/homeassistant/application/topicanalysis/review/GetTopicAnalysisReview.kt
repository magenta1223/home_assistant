package com.homeassistant.application.topicanalysis.review

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId

data class GetTopicAnalysisReviewRequest(
    val reviewId: String,
    val userId: String,
)

/** Retrieves a topic-analysis review after authorization and ownership checks. */
fun interface GetTopicAnalysisReviewUseCase {
    /** Returns a review after checking that the caller may access it. */
    fun get(request: GetTopicAnalysisReviewRequest): TopicAnalysisReview
}

class GetTopicAnalysisReview(
    private val reviews: TopicAnalysisReviewStore,
    private val accessPolicy: HouseholdAccessPolicy,
) : GetTopicAnalysisReviewUseCase {
    override fun get(request: GetTopicAnalysisReviewRequest): TopicAnalysisReview {
        val userId = UserId(request.userId)
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
        val review = reviews.find(request.reviewId)
            ?: throw TopicAnalysisReviewNotFoundException(request.reviewId)
        if (review.requestedBy != userId) throw HouseholdAccessDeniedException()
        return review
    }
}
