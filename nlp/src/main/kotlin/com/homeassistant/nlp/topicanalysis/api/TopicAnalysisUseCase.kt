package com.homeassistant.nlp.topicanalysis.api

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

