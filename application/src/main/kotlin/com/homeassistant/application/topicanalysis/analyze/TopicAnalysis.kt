package com.homeassistant.application.topicanalysis.analyze

/** Imports source records, analyzes them, and creates a reviewable topic proposal. */
interface TopicAnalysis {
    /** Imports new source records and creates a topic-analysis review. */
    suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult
}