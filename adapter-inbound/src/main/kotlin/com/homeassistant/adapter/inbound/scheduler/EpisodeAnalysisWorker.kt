package com.homeassistant.adapter.inbound.scheduler

import com.homeassistant.application.port.inbound.memoryextraction.AnalyzeSubmittedSources
import com.homeassistant.application.port.inbound.memoryextraction.SourceAnalysisRoundResult
import com.homeassistant.infrastructure.runtime.AbstractRuntime
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs [AnalyzeSubmittedSources] every [intervalSeconds] until no job is ready and logs each outcome. Every round
 * runs on the one thread of [executor], so two analyses never call the language model at the same time.
 */
class EpisodeAnalysisWorker(
    private val analyzeSubmittedSources: AnalyzeSubmittedSources,
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "episode-analysis-jobs").apply { isDaemon = true }
    },
) : AbstractRuntime() {
    override fun onStart() {
        executor.scheduleWithFixedDelay(::analyzeReadyJobs, 0, intervalSeconds, TimeUnit.SECONDS)
    }

    /**
     * Analyzes ready jobs one at a time until none is left, so a queued job does not wait a whole interval behind
     * another. Contains every failure: an exception escaping a scheduled task would cancel later runs.
     */
    internal fun analyzeReadyJobs() {
        runCatching { runBlocking { analyzeUntilIdle() } }
            .onFailure { log.warn("Episode analysis round failed", it) }
    }

    override fun onStop() {
        executor.shutdownNow()
    }

    private suspend fun analyzeUntilIdle() {
        do {
            val round = analyzeSubmittedSources.analyzeNextJob()
            log(round)
        } while (round != SourceAnalysisRoundResult.Idle)
    }

    private fun log(round: SourceAnalysisRoundResult) {
        when (round) {
            SourceAnalysisRoundResult.Idle -> Unit
            is SourceAnalysisRoundResult.Completed ->
                log.info("Episode analysis job completed jobId={} episodes={}", round.jobId, round.episodeCount)
            is SourceAnalysisRoundResult.Failed ->
                log.warn("Episode analysis job failed jobId={} reason={}", round.jobId, round.reason)
        }
    }

    private companion object {
        const val DEFAULT_INTERVAL_SECONDS = 10L
        val log = LoggerFactory.getLogger(EpisodeAnalysisWorker::class.java)
    }
}
