package com.homeassistant.application.usecase.memoryextraction.episode

import com.homeassistant.application.port.inbound.memoryextraction.AnalyzeSubmittedSources
import com.homeassistant.application.port.inbound.memoryextraction.SourceAnalysisRoundResult
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeAnalysisJob
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeAnalysisJobQueue
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeAnalysisSubmission
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeExtractor
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpretation
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpretationResult
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpreter
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceReferenceDraft
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Claims submitted analyses from the [EpisodeAnalysisJobQueue] one at a time, turns their pending records and
 * interpreted reference into episodes, and records each outcome on the job. A failed job is retried after
 * [retryDelayMillis] until [maxAttempts]; a job left in progress longer than [processingLeaseMillis] is reclaimed.
 */
class AnalyzeSubmittedSourcesService(
    private val jobs: EpisodeAnalysisJobQueue,
    private val sourceRecords: SourceRecordRepository,
    private val episodes: EpisodeRepository,
    private val episodeExtractor: EpisodeExtractor,
    private val referenceInterpreter: SourceReferenceInterpreter,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    private val processingLeaseMillis: Long = DEFAULT_PROCESSING_LEASE_MILLIS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) : AnalyzeSubmittedSources {
    init {
        require(retryDelayMillis >= 0) { "retryDelayMillis must not be negative" }
        require(processingLeaseMillis > 0) { "processingLeaseMillis must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    override suspend fun analyzeNextJob(): SourceAnalysisRoundResult {
        val job = claimNext() ?: return SourceAnalysisRoundResult.Idle
        return runCatching { analyze(job.submission) }
            .fold(
                onSuccess = { episodeCount ->
                    jobs.markCompleted(job.id, job.attempt, episodeCount, clock.millis())
                    SourceAnalysisRoundResult.Completed(job.id, episodeCount)
                },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    val reason = failure.message ?: failure.javaClass.simpleName
                    jobs.markFailed(job.id, job.attempt, reason, clock.millis())
                    SourceAnalysisRoundResult.Failed(job.id, reason)
                },
            )
    }

    /** The oldest job that is ready now. */
    private fun claimNext(): EpisodeAnalysisJob? {
        val now = clock.millis()
        return jobs.claimNext(
            now = now,
            retryBefore = now - retryDelayMillis,
            staleProcessingBefore = now - processingLeaseMillis,
            maxAttempts = maxAttempts,
        )
    }

    /** Saves the episodes of [submission]'s records still pending analysis and returns how many were saved. */
    private suspend fun analyze(submission: EpisodeAnalysisSubmission): Int {
        val records = recordsToAnalyze(submission)
        if (records.isEmpty()) return 0
        val proposals = episodeExtractor.analyze(SourceDocument(submission.source, records))
        episodes.save(EpisodeBatch(submission.requestedBy, proposals, records.map { it.id }))
        return proposals.size
    }

    /** [submission]'s typed records still pending analysis, then the pending records of its interpreted reference. */
    private suspend fun recordsToAnalyze(submission: EpisodeAnalysisSubmission): List<SourceRecord> =
        (sourceRecords.findPendingByIds(submission.sourceRecordIds) + referenceRecords(submission)).distinctBy { it.id }

    /** The pending records stored from [submission]'s interpreted reference; empty when it has none. */
    private suspend fun referenceRecords(submission: EpisodeAnalysisSubmission): List<SourceRecord> {
        val referenceId = submission.referenceId ?: return emptyList()
        val reference = checkNotNull(sourceRecords.findReferenceContent(referenceId)) { "reference $referenceId is not stored" }
        val drafts = interpretSegments(reference).map { it.toRecordOf(reference) }
        return when (val saved = sourceRecords.save(submission.source, drafts, submission.access)) {
            is SourceRecordSaveResult.Saved -> saved.recordsToAnalyze
            is SourceRecordSaveResult.AccessConflict ->
                error("reference records already exist with a different audience: ${submission.source.name}")
        }
    }

    /** The segments of [reference]; fails on a rejected file and an empty result. */
    private suspend fun interpretSegments(reference: SourceReferenceDraft): List<SourceReferenceInterpretation> {
        val segments = when (val result = referenceInterpreter.interpret(reference)) {
            is SourceReferenceInterpretationResult.Interpreted -> result.segments
            is SourceReferenceInterpretationResult.Rejected -> error(result.reason)
        }
        check(segments.isNotEmpty()) { "reference did not contain interpretable content" }
        return segments
    }

    /** This segment as a record of [reference], keyed by the file hash and segment so a re-upload is deduplicated. */
    private fun SourceReferenceInterpretation.toRecordOf(reference: SourceReferenceDraft): SourceRecordDraft =
        SourceRecordDraft(
            deduplicationKey = "reference:${reference.sha256}:$segmentKey",
            content = buildString {
                appendLine("[reference: ${reference.fileName}, $segmentKey]")
                append(content.trim())
            },
            reference = reference,
        )

    companion object {
        const val DEFAULT_RETRY_DELAY_MILLIS = 60_000L
        const val DEFAULT_PROCESSING_LEASE_MILLIS = 10 * 60_000L
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}
