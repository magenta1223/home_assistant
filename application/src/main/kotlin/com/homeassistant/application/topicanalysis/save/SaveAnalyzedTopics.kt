package com.homeassistant.application.topicanalysis.save

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReview
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReviewNotFoundException
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.domain.topicanalysis.TopicProposal
import com.homeassistant.application.memory.answer.MemorySearchIndex

interface SaveAnalyzedTopicsUseCase {
    fun saveAll(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult
    fun saveSelected(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult
}

class SaveAnalyzedTopics(
    private val topicRepository: TopicAnalysisStore,
    private val reviewStore: TopicAnalysisReviewStore,
    memorySearchIndex: MemorySearchIndex,
    indexingOutbox: IndexingOutboxStore,
    private val accessPolicy: HouseholdAccessPolicy,
) : SaveAnalyzedTopicsUseCase {
    private val memoryIndexing = MemoryIndexingCoordinator(
        topicRepository,
        memorySearchIndex,
        indexingOutbox,
    )

    override fun saveAll(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        val review = reviewStore.find(request.previewId)
            ?: throw TopicAnalysisReviewNotFoundException(request.previewId)
        requireReviewOwner(review, userId)
        return saveReviewProposals(review, review.proposals, userId)
    }

    override fun saveSelected(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        val review = reviewStore.find(request.previewId)
            ?: throw TopicAnalysisReviewNotFoundException(request.previewId)
        requireReviewOwner(review, userId)
        val selectedTopics = request.selectedTopicIndices
            .sorted()
            .mapNotNull { index -> review.proposals.getOrNull(index) }
        return saveReviewProposals(review, selectedTopics, userId)
    }

    private fun saveReviewProposals(
        review: TopicAnalysisReview,
        topics: List<TopicProposal>,
        userId: UserId,
    ): TopicAnalysisSaveResult {
        if (topics.isEmpty()) return TopicAnalysisSaveResult(review.id, emptyList())

        val savedTopics = topics.map { proposal ->
            topicRepository.createTopic(proposal, userId, review.source.type, review.source.name)
        }
        savedTopics.forEach(memoryIndexing::index)
        memoryIndexing.retryPending(
            savedTopics.flatMapTo(mutableSetOf()) { topic -> topic.memories.map { it.id } },
        )
        return TopicAnalysisSaveResult(review.id, savedTopics)
    }

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
    }

    private fun requireReviewOwner(review: TopicAnalysisReview, userId: UserId) {
        if (review.requestedBy != userId) throw HouseholdAccessDeniedException()
    }
}
