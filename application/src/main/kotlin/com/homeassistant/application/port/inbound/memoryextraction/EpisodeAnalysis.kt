package com.homeassistant.application.port.inbound.memoryextraction

/** Accepts source records for episode analysis. */
interface EpisodeAnalysis {
    /** Stores the new source records and reference of [request], queues their analysis and returns the queued job. */
    fun submit(request: EpisodeAnalysisRequest): EpisodeAnalysisSubmissionResult
}
