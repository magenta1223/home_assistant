package com.homeassistant.adapter.inbound.http

import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.TopicProposal
import com.homeassistant.application.topicanalysis.analyze.DuplicateSourceRecordsException
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReviewNotFoundException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisResult
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysis
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveRequest
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveResult
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSelectionSaveRequest
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase
import com.homeassistant.domain.source.SourceDescriptor

internal object FakeAnalyzer : TopicAnalysis, SaveAnalyzedTopicsUseCase {
    var sourceFileName = ""
    var text = ""
    var previewId = ""
    var lastPreviewUserId = ""
    var previewCalls = 0
    var saveCalls = 0

    fun reset() {
        sourceFileName = ""
        text = ""
        previewId = ""
        lastPreviewUserId = ""
        previewCalls = 0
        saveCalls = 0
    }

    override suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult {
        lastPreviewUserId = request.userId
        sourceFileName = request.source.source.name
        text = request.source.records.singleOrNull()?.content.orEmpty()
        previewCalls += 1
        if (request.source.source.name == "duplicate.txt") {
            throw DuplicateSourceRecordsException(request.source.source.name, 1)
        }
        return TopicAnalysisResult(
            previewId = "preview-1",
            sourceType = request.source.source.type,
            sourceName = request.source.source.name,
            importedRecordCount = 1,
            topics = listOf(newTopic(request.source.source.name, 1)),
        )
    }

    override fun saveAll(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult {
        lastPreviewUserId = request.userId
        previewId = request.previewId
        saveCalls += 1
        if (request.previewId == "missing") throw TopicAnalysisReviewNotFoundException(request.previewId)
        if (request.previewId == "broken") error("database unavailable")
        return TopicAnalysisSaveResult(
            previewId = request.previewId,
            topics = listOf(topic("2026-06-07.txt", 11)),
        )
    }

    override fun saveSelected(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult =
        saveAll(TopicAnalysisSaveRequest(request.previewId, request.userId))

    private fun newTopic(sourceName: String, evidenceRef: Int) =
        TopicProposal(
            title = "관계 표현",
            summary = "애정 표현을 주고받았다.",
            categories = listOf("relationship"),
            memories = listOf(candidateMemory(evidenceRef)),
        )

    private fun topic(sourceName: String, evidenceRef: Int) =
        Topic(
            id = 7,
            createdByUserId = "dad",
            sourceType = "kakao",
            sourceName = sourceName,
            title = "관계 표현",
            summary = "애정 표현을 주고받았다.",
            categories = listOf("relationship"),
            memories = listOf(
                Memory(
                    id = 8,
                    topicId = 7,
                    createdByUserId = "dad",
                    content = "동훈은 애정 표현을 했다.",
                    subject = "동훈",
                    memoryType = MemoryType.STATE,
                    certainty = MemoryCertainty.OBSERVED,
                    visibility = com.homeassistant.domain.memory.MemoryVisibility.FAMILY,
                    evidenceRefs = listOf(evidenceRef),
                ),
            ),
        )

    private fun candidateMemory(evidenceRef: Int) =
        MemoryProposal(
            content = "동훈은 애정 표현을 했다.",
            subject = "동훈",
            memoryType = MemoryType.STATE,
            certainty = MemoryCertainty.OBSERVED,
            evidenceIds = listOf(evidenceRef),
        )
}
