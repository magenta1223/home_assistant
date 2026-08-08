package com.homeassistant.application.port.input.memory.analysis

/** Imports source records, extracts flat memories, and saves them. */
interface MemoryAnalysis {
    /** Imports new source records, extracts flat memories, and saves them. */
    suspend fun execute(request: MemoryAnalysisRequest): MemoryAnalysisResult
}
