package com.homeassistant.application.port.inbound.episode.analysis

/** Imports source records, extracts flat episodes, and saves them. */
interface EpisodeAnalysis {
    /** Imports new source records, extracts flat episodes, and saves them. */
    suspend fun execute(request: EpisodeAnalysisRequest): EpisodeAnalysisResult
}
