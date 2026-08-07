package com.homeassistant.application.memory.analysis

/** Imports source records, extracts flat memories, and saves them. */
interface MemoryAnalysis {
    /** Imports new source records, extracts flat memories, and saves them. */
    suspend fun execute(request: MemoryAnalysisRequest): MemoryAnalysisResult
}
