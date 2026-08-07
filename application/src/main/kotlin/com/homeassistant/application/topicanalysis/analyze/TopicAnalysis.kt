package com.homeassistant.application.topicanalysis.analyze

/** Imports source records, analyzes them, and saves the resulting topics. */
interface TopicAnalysis {
    /** Imports new source records, analyzes them, and saves the resulting topics. */
    suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult
}
