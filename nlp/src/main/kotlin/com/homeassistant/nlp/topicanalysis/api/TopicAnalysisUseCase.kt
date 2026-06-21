package com.homeassistant.nlp.topicanalysis.api

abstract class TopicAnalysisUseCase {
    protected abstract val topicAnalyzer: TopicAnalyzer
    abstract suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult

    abstract suspend fun saveAnalysis(previewId: String): TopicAnalysisSaveResult
}

