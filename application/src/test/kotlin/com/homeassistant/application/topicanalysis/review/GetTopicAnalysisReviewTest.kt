package com.homeassistant.application.topicanalysis.review

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.topicanalysis.TopicProposal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GetTopicAnalysisReviewTest {
    @Test
    fun `returns review to its requester`() {
        val useCase = GetTopicAnalysisReview(FakeReviewStore(review()), AUTHORIZED_USERS)

        val result = useCase.get(GetTopicAnalysisReviewRequest("review-1", "dad"))

        assertEquals("review-1", result.id)
    }

    @Test
    fun `rejects another household user`() {
        val useCase = GetTopicAnalysisReview(FakeReviewStore(review()), AUTHORIZED_USERS)

        assertFailsWith<HouseholdAccessDeniedException> {
            useCase.get(GetTopicAnalysisReviewRequest("review-1", "mom"))
        }
    }

    @Test
    fun `reports a missing review`() {
        val useCase = GetTopicAnalysisReview(FakeReviewStore(null), AUTHORIZED_USERS)

        assertFailsWith<TopicAnalysisReviewNotFoundException> {
            useCase.get(GetTopicAnalysisReviewRequest("missing", "dad"))
        }
    }
}

private class FakeReviewStore(private val review: TopicAnalysisReview?) : TopicAnalysisReviewStore {
    override fun create(
        requestedBy: UserId,
        source: SourceDescriptor,
        proposals: List<TopicProposal>,
    ): TopicAnalysisReview = error("not used")

    override fun find(reviewId: String): TopicAnalysisReview? = review?.takeIf { it.id == reviewId }
}

private fun review() =
    TopicAnalysisReview(
        id = "review-1",
        requestedBy = UserId("dad"),
        source = SourceDescriptor("kakao", "family.txt"),
        proposals = emptyList(),
    )

private val AUTHORIZED_USERS = HouseholdAccessPolicy { it.value in setOf("dad", "mom") }
