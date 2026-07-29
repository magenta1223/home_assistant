package com.homeassistant.app.routes

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.datamodel.topicanalysis.ClaimCertainty
import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import com.homeassistant.datamodel.topicanalysis.TopicClaim
import com.homeassistant.datamodel.topicanalysis.TopicClaimCandidate
import com.homeassistant.nlp.topicanalysis.api.DuplicateKakaoMessagesException
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisPreviewNotFoundException
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSaveRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSaveResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisUseCase

internal object FakeAnalyzer : TopicAnalysisUseCase {
    var sourceFileName = ""
    var text = ""
    var previewId = ""
    var previewCalls = 0
    var saveCalls = 0

    fun reset() {
        sourceFileName = ""
        text = ""
        previewId = ""
        previewCalls = 0
        saveCalls = 0
    }

    override suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult {
        sourceFileName = request.sourceName
        text = request.text
        previewCalls += 1
        if (request.text == "duplicate") {
            throw DuplicateKakaoMessagesException(request.sourceName, 1)
        }
        return TopicAnalysisResult(
            previewId = "preview-1",
            sourceType = request.sourceType,
            sourceName = request.sourceName,
            importedRecordCount = 1,
            topics = listOf(newTopic(request.sourceName, 1)),
        )
    }

    override suspend fun saveAnalysis(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult {
        previewId = request.previewId
        saveCalls += 1
        if (request.previewId == "missing") throw TopicAnalysisPreviewNotFoundException(request.previewId)
        if (request.previewId == "broken") error("database unavailable")
        return TopicAnalysisSaveResult(
            previewId = request.previewId,
            topics = listOf(topic("2026-06-07.txt", 11)),
        )
    }

    private fun newTopic(sourceName: String, evidenceRef: Int) =
        TopicCandidate(
            familyId = "family-1",
            createdByUserId = "dad",
            sourceType = "kakao",
            sourceName = sourceName,
            title = "관계 표현",
            summary = "애정 표현을 주고받았다.",
            memoryTypes = listOf(MemoryType.STATE),
            domains = listOf("relationship"),
            evidenceRefs = listOf(evidenceRef),
            claims = listOf(candidateClaim(evidenceRef)),
        )

    private fun topic(sourceName: String, evidenceRef: Int) =
        Topic(
            id = 7,
            familyId = "family-1",
            createdByUserId = "dad",
            sourceType = "kakao",
            sourceName = sourceName,
            title = "관계 표현",
            summary = "애정 표현을 주고받았다.",
            memoryTypes = listOf(MemoryType.STATE),
            domains = listOf("relationship"),
            evidenceRefs = listOf(evidenceRef),
            claims = listOf(
                TopicClaim(
                    id = 8,
                    text = "동훈은 애정 표현을 했다.",
                    subject = "동훈",
                    memoryType = MemoryType.STATE,
                    certainty = ClaimCertainty.OBSERVED,
                    evidenceRefs = listOf(evidenceRef),
                ),
            ),
            status = CandidateStatus.PENDING,
        )

    private fun candidateClaim(evidenceRef: Int) =
        TopicClaimCandidate(
            text = "동훈은 애정 표현을 했다.",
            subject = "동훈",
            memoryType = MemoryType.STATE,
            certainty = ClaimCertainty.OBSERVED,
            evidenceRefs = listOf(evidenceRef),
        )
}
