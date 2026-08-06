package com.homeassistant.application.topicanalysis.save

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreview
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.domain.topicanalysis.TopicProposal
import com.homeassistant.application.memory.answer.MemorySearchIndex

interface SaveAnalyzedTopicsUseCase {
    fun saveAll(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult
    fun saveSelected(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult
}

class SaveAnalyzedTopics(
    private val topicRepository: TopicAnalysisStore,
    private val previewRepository: TopicAnalysisPreviewStore,
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
        val preview = previewRepository.findPreview(request.previewId)
            ?: throw TopicAnalysisPreviewNotFoundException(request.previewId)
        requirePreviewOwner(preview, userId)
        return savePreviewTopics(preview, preview.topics, userId)
    }

    override fun saveSelected(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        val preview = previewRepository.findPreview(request.previewId)
            ?: throw TopicAnalysisPreviewNotFoundException(request.previewId)
        requirePreviewOwner(preview, userId)
        val selectedTopics = request.selectedTopicIndices
            .sorted()
            .mapNotNull { index -> preview.topics.getOrNull(index) }
        return savePreviewTopics(preview, selectedTopics, userId)
    }

    private fun savePreviewTopics(
        preview: TopicAnalysisPreview,
        topics: List<TopicProposal>,
        userId: UserId,
    ): TopicAnalysisSaveResult {
        if (topics.isEmpty()) return TopicAnalysisSaveResult(preview.previewId, emptyList())

        val savedTopics = topics.map { proposal ->
            topicRepository.createTopic(proposal, userId, preview.sourceType, preview.sourceName)
        }
        savedTopics.forEach(memoryIndexing::index)
        memoryIndexing.retryPending(
            savedTopics.flatMapTo(mutableSetOf()) { topic -> topic.memories.map { it.id } },
        )
        return TopicAnalysisSaveResult(preview.previewId, savedTopics)
    }

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
    }

    private fun requirePreviewOwner(preview: TopicAnalysisPreview, userId: UserId) {
        if (preview.requestedByUserId != userId.value) throw HouseholdAccessDeniedException()
    }
}
