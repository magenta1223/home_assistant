package com.homeassistant.application.usecase.sourceimport

import com.homeassistant.application.port.inbound.sourceimport.AnalyzeSubmittedSources
import com.homeassistant.application.port.inbound.sourceimport.SourceAnalysisRoundResult
import com.homeassistant.application.port.outbound.sourceimport.SourceImportJob
import com.homeassistant.application.port.outbound.sourceimport.SourceImportJobQueue
import com.homeassistant.application.port.outbound.sourceimport.SourceImportSubmission
import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpretation
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpretationResult
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpreter
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceReferenceDraft
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Claims submitted analyses from the [SourceImportJobQueue] one at a time, stores the records interpreted from their
 * reference, and records each outcome on the job. Episodes are extracted from the stored records by the memory update.
 * A failed job is retried after [retryDelayMillis] until [maxAttempts]; a job left in progress longer than
 * [processingLeaseMillis] is reclaimed.
 */
class AnalyzeSubmittedSourcesService(
    private val jobs: SourceImportJobQueue,
    private val sourceRecords: SourceRecordRepository,
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
        return runCatching { storeRecords(job.submission) }
            .fold(
                onSuccess = { recordCount ->
                    jobs.markCompleted(job.id, job.attempt, recordCount, clock.millis())
                    SourceAnalysisRoundResult.Completed(job.id, recordCount)
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
    private fun claimNext(): SourceImportJob? {
        val now = clock.millis()
        return jobs.claimNext(
            now = now,
            retryBefore = now - retryDelayMillis,
            staleProcessingBefore = now - processingLeaseMillis,
            maxAttempts = maxAttempts,
        )
    }

    /**
     * Stores the records of [submission]'s interpreted reference and returns how many source records it covers: its
     * newly submitted records and every record of its reference.
     */
    private suspend fun storeRecords(submission: SourceImportSubmission): Int =
        (submission.sourceRecordIds + referenceRecords(submission).map { it.id }).distinct().size

    /** The records of [submission]'s interpreted reference, stored now or by an earlier attempt; empty when it has none. */
    private suspend fun referenceRecords(submission: SourceImportSubmission): List<SourceRecord> {
        val referenceId = submission.referenceId ?: return emptyList()
        val reference = checkNotNull(sourceRecords.findReferenceContent(referenceId)) { "reference $referenceId is not stored" }
        val drafts = interpretSegments(reference).map { it.toRecordOf(reference) }
        return when (val saved = sourceRecords.save(submission.source, drafts, submission.requestedBy, submission.access)) {
            is SourceRecordSaveResult.Saved -> saved.newRecords + saved.alreadyStoredRecords
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
