package com.homeassistant.nlp.topicanalysis.api

abstract class TopicAnalysisUseCase {
    abstract suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult

    abstract suspend fun saveAnalysis(previewId: String): TopicAnalysisSaveResult

    open suspend fun saveSelectedAnalysis(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult =
        saveAnalysis(request.previewId)
}

abstract class TopicAnalysisModelEvalUseCase {
    abstract suspend fun runBundledKakaoAsset(): TopicAnalysisModelEvalRunResult
}

