package com.homeassistant.adapter.inbound.scheduler

import com.homeassistant.application.port.inbound.sourceimport.AnalyzeSubmittedSources
import com.homeassistant.application.port.inbound.sourceimport.SourceAnalysisRoundResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceImportWorkerTest {
    @Test
    fun `a run analyzes ready jobs until none is left`() {
        val rounds = ArrayDeque(
            listOf(
                SourceAnalysisRoundResult.Completed(jobId = 1, recordCount = 2),
                SourceAnalysisRoundResult.Failed(jobId = 2, reason = "extractor unavailable"),
                SourceAnalysisRoundResult.Idle,
                SourceAnalysisRoundResult.Completed(jobId = 3, recordCount = 1),
            ),
        )
        SourceImportWorker(AnalyzeSubmittedSources { rounds.removeFirst() }).analyzeReadyJobs()
        assertEquals(listOf(SourceAnalysisRoundResult.Completed(jobId = 3, recordCount = 1)), rounds.toList())
    }

    @Test
    fun `a failing round is contained`() {
        SourceImportWorker(AnalyzeSubmittedSources { error("store unavailable") }).analyzeReadyJobs()
    }
}
