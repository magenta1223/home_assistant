package com.homeassistant.application.topicanalysis

import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisResult
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveRequest
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveResult
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSelectionSaveRequest

interface TopicAnalysisUseCase {
    suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult

    suspend fun saveAnalysis(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult

    suspend fun saveSelectedAnalysis(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult =
        saveAnalysis(
            TopicAnalysisSaveRequest(
                previewId = request.previewId,
                userId = request.userId,
                familyId = request.familyId,
            ),
        )
}
