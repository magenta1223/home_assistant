package com.homeassistant.application.topicanalysis.save

import com.homeassistant.domain.identity.FamilyId
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.HouseholdAccessScope
import com.homeassistant.domain.identity.UserId
import com.homeassistant.application.topicanalysis.analyze.SourceTextParser
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.kakao.KakaoImporter
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.domain.topicanalysis.TopicCandidate
import com.homeassistant.application.topicanswer.answer.TopicClaimSearchIndex

interface SaveTopicCandidatesUseCase {
    fun saveAll(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult
    fun saveSelected(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult
}

internal class SaveTopicCandidates(
    private val importService: KakaoImporter,
    private val sourceTextParser: SourceTextParser,
    private val topicRepository: TopicAnalysisStore,
    private val previewRepository: TopicAnalysisPreviewStore,
    topicClaimSearchIndex: TopicClaimSearchIndex,
    indexingOutbox: IndexingOutboxStore,
    private val accessPolicy: HouseholdAccessPolicy,
) : SaveTopicCandidatesUseCase {
    private val topicIndexing = TopicIndexingCoordinatorFactory.create(
        topicRepository,
        topicClaimSearchIndex,
        indexingOutbox,
    )

    override fun saveAll(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult {
        val scope = request.scope()
        requireAuthorized(scope)
        val preview = previewRepository.findPreview(request.previewId)
            ?: throw TopicAnalysisPreviewNotFoundException(request.previewId)
        requirePreviewScope(preview.topics, scope)
        return savePreviewTopics(request.previewId, preview.sourceFileName, preview.text, preview.topics)
    }

    override fun saveSelected(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult {
        val scope = request.scope()
        requireAuthorized(scope)
        val preview = previewRepository.findPreview(request.previewId)
            ?: throw TopicAnalysisPreviewNotFoundException(request.previewId)
        requirePreviewScope(preview.topics, scope)
        val selectedTopics = request.selectedTopicIndices
            .sorted()
            .mapNotNull { index -> preview.topics.getOrNull(index) }
        return savePreviewTopics(request.previewId, preview.sourceFileName, preview.text, selectedTopics)
    }

    private fun savePreviewTopics(
        previewId: String,
        sourceFileName: String,
        text: String,
        topics: List<TopicCandidate>,
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
        savedTopics.forEach(topicIndexing::index)
        topicIndexing.retryPending(savedTopics.mapTo(mutableSetOf()) { it.id })
        return TopicAnalysisSaveResult(previewId, savedTopics)
    }

    private fun TopicCandidate.remapEvidenceRefs(refToStoredId: Map<Int, Int>): TopicCandidate =
        copy(
            evidenceRefs = evidenceRefs.map { refToStoredId.getValue(it) },
            claims = claims.map { claim ->
                claim.copy(evidenceRefs = claim.evidenceRefs.map { refToStoredId.getValue(it) })
            },
        )

    private fun TopicAnalysisSaveRequest.scope(): HouseholdAccessScope =
        HouseholdAccessScope(UserId(userId), FamilyId(familyId))

    private fun TopicAnalysisSelectionSaveRequest.scope(): HouseholdAccessScope =
        HouseholdAccessScope(UserId(userId), FamilyId(familyId))

    private fun requireAuthorized(scope: HouseholdAccessScope) {
        if (!accessPolicy.isAuthorized(scope)) throw HouseholdAccessDeniedException()
    }

    private fun requirePreviewScope(topics: List<TopicCandidate>, scope: HouseholdAccessScope) {
        if (topics.any { it.familyId != scope.familyId.value || it.createdByUserId != scope.userId.value }) {
            throw HouseholdAccessDeniedException()
        }
    }
}
