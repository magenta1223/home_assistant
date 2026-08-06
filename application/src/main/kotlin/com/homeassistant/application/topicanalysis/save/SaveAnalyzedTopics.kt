package com.homeassistant.application.topicanalysis.save

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.application.topicanalysis.analyze.SourceTextParser
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.kakao.KakaoImporter
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.domain.topicanalysis.ProposedTopic
import com.homeassistant.application.memory.answer.MemorySearchIndex

interface SaveAnalyzedTopicsUseCase {
    fun saveAll(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult
    fun saveSelected(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult
}

class SaveAnalyzedTopics(
    private val importService: KakaoImporter,
    private val sourceTextParser: SourceTextParser,
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
        return savePreviewTopics(request.previewId, preview.sourceFileName, preview.text, preview.topics)
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
        return savePreviewTopics(request.previewId, preview.sourceFileName, preview.text, selectedTopics)
    }

    private fun savePreviewTopics(
        previewId: String,
        sourceFileName: String,
        text: String,
        topics: List<ProposedTopic>,
    ): TopicAnalysisSaveResult {
        if (topics.isEmpty()) return TopicAnalysisSaveResult(previewId, emptyList())

        val parsed = sourceTextParser.parse(sourceFileName, text)
        val imported = importService.import(sourceFileName, parsed)
        val refToStoredId = parsed.mapIndexed { index, message ->
            index + 1 to imported.messages.first { it.fingerprint == message.fingerprint }.id
        }.toMap()
        val savedTopics = topics.map { topic ->
            topicRepository.createTopic(topic.remapEvidenceRefs(refToStoredId))
        }
        savedTopics.forEach(memoryIndexing::index)
        memoryIndexing.retryPending(
            savedTopics.flatMapTo(mutableSetOf()) { topic -> topic.memories.map { it.id } },
        )
        return TopicAnalysisSaveResult(previewId, savedTopics)
    }

    private fun ProposedTopic.remapEvidenceRefs(refToStoredId: Map<Int, Int>): ProposedTopic =
        copy(
            evidenceRefs = evidenceRefs.map { refToStoredId.getValue(it) },
            memories = memories.map { memory ->
                memory.copy(evidenceRefs = memory.evidenceRefs.map { refToStoredId.getValue(it) })
            },
        )

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
    }

    private fun requirePreviewOwner(topics: List<ProposedTopic>, userId: UserId) {
        if (topics.any { it.createdByUserId != userId.value }) {
            throw HouseholdAccessDeniedException()
        }
    }
}
