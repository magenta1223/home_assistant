package com.homeassistant.application.port.inbound.memoryextraction

/** Turns submitted source analyses into episodes, one job at a time. */
fun interface AnalyzeSubmittedSources {
    /**
     * Claims the oldest ready analysis job, extracts episodes from its records still pending analysis and from its
     * interpreted reference, saves them and records the outcome on the job.
     */
    suspend fun analyzeNextJob(): SourceAnalysisRoundResult
}

/** How one [AnalyzeSubmittedSources.analyzeNextJob] round ended. */
sealed interface SourceAnalysisRoundResult {
    /** No job was ready; nothing was read or written. */
    data object Idle : SourceAnalysisRoundResult

    /** Job [jobId] saved [episodeCount] episodes; zero when none of its records were still pending. */
    data class Completed(val jobId: Int, val episodeCount: Int) : SourceAnalysisRoundResult

    /** Job [jobId] failed for [reason] and waits for a retry until its attempt limit is reached. */
    data class Failed(val jobId: Int, val reason: String) : SourceAnalysisRoundResult
}
