package com.homeassistant.application.port.inbound.memoryextraction

/** Accepts source records for episode analysis and reports how each submitted analysis is going. */
interface EpisodeAnalysis {
    /** Stores the new source records and reference of [request], queues their analysis and returns the queued job. */
    fun submit(request: EpisodeAnalysisRequest): EpisodeAnalysisSubmissionResult

    /** The analysis [jobId] when [userId] submitted it, otherwise null. */
    fun findJob(userId: String, jobId: Int): EpisodeAnalysisJobReport?
}
