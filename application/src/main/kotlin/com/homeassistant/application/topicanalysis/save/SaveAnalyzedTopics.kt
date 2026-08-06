package com.homeassistant.application.topicanalysis.save

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.domain.topicanalysis.ProposedTopic
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
    private val memoryIndexing = MemoryIndexingCoordinatorFactory.create(
        topicRepository,
        memorySearchIndex,
        indexingOutbox,
    )

    override fun saveAll(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        val preview = previewRepository.findPreview(request.previewId)
            ?: throw TopicAnalysisPreviewNotFoundException(request.previewId)
        requirePreviewOwner(preview.topics, userId)
        return savePreviewTopics(request.previewId, preview.topics)
    }

    override fun saveSelected(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        val preview = previewRepository.findPreview(request.previewId)
            ?: throw TopicAnalysisPreviewNotFoundException(request.previewId)
        requirePreviewOwner(preview.topics, userId)
        val selectedTopics = request.selectedTopicIndices
            .sorted()
            .mapNotNull { index -> preview.topics.getOrNull(index) }
        return savePreviewTopics(request.previewId, selectedTopics)
    }

    private fun savePreviewTopics(
        previewId: String,
        topics: List<ProposedTopic>,
    ): TopicAnalysisSaveResult {
        if (topics.isEmpty()) return TopicAnalysisSaveResult(previewId, emptyList())

        val savedTopics = topics.map(topicRepository::createTopic)
        savedTopics.forEach(memoryIndexing::index)
        memoryIndexing.retryPending(
            savedTopics.flatMapTo(mutableSetOf()) { topic -> topic.memories.map { it.id } },
        )
        return TopicAnalysisSaveResult(previewId, savedTopics)
    }

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
    }

    private fun requirePreviewOwner(topics: List<ProposedTopic>, userId: UserId) {
        if (topics.any { it.createdByUserId != userId.value }) {
            throw HouseholdAccessDeniedException()
        }
    }
}
