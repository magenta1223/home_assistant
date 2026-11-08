package com.homeassistant.adapter.inbound.scheduler

import com.homeassistant.application.port.inbound.memoryextraction.AnalyzeSubmittedSources
import com.homeassistant.application.port.inbound.memoryextraction.SourceAnalysisRoundResult
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeAnalysisWorkerTest {
    @Test
    fun `a run analyzes ready jobs until none is left`() {
        val rounds = ArrayDeque(
            listOf(
                SourceAnalysisRoundResult.Completed(jobId = 1, episodeCount = 2),
                SourceAnalysisRoundResult.Failed(jobId = 2, reason = "extractor unavailable"),
                SourceAnalysisRoundResult.Idle,
                SourceAnalysisRoundResult.Completed(jobId = 3, episodeCount = 1),
            ),
        )
        EpisodeAnalysisWorker(AnalyzeSubmittedSources { rounds.removeFirst() }).analyzeReadyJobs()
        assertEquals(listOf(SourceAnalysisRoundResult.Completed(jobId = 3, episodeCount = 1)), rounds.toList())
    }

    @Test
    fun `a failing round is contained`() {
        EpisodeAnalysisWorker(AnalyzeSubmittedSources { error("store unavailable") }).analyzeReadyJobs()
    }
}
