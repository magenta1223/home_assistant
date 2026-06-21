package com.homeassistant.nlp.topicanalysis.api

interface TopicAnalysisUseCase {
    suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult

    suspend fun saveAnalysis(previewId: String): TopicAnalysisSaveResult
}

