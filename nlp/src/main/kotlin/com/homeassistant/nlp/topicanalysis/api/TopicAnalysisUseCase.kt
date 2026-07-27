package com.homeassistant.nlp.topicanalysis.api

abstract class TopicAnalysisUseCase {
    abstract suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult

    abstract suspend fun saveAnalysis(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult

    open suspend fun saveSelectedAnalysis(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult =
        saveAnalysis(
            TopicAnalysisSaveRequest(
                previewId = request.previewId,
                userId = request.userId,
                familyId = request.familyId,
            ),
        )
}

