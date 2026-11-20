package com.homeassistant.application.port.inbound.memoryextraction

/** Completes submitted source analyses one job at a time, so every record they cover is stored for memory updates. */
fun interface AnalyzeSubmittedSources {
    /**
     * Claims the oldest ready analysis job, stores the records interpreted from its reference, and records on the job
     * how many source records it covers.
     */
    suspend fun analyzeNextJob(): SourceAnalysisRoundResult
}

/** How one [AnalyzeSubmittedSources.analyzeNextJob] round ended. */
sealed interface SourceAnalysisRoundResult {
    /** No job was ready; nothing was read or written. */
    data object Idle : SourceAnalysisRoundResult

    /** Job [jobId] covers [recordCount] stored source records: its submitted records and its interpreted reference. */
    data class Completed(val jobId: Int, val recordCount: Int) : SourceAnalysisRoundResult

    /** Job [jobId] failed for [reason] and waits for a retry until its attempt limit is reached. */
    data class Failed(val jobId: Int, val reason: String) : SourceAnalysisRoundResult
}
