package com.homeassistant.application.usecase.memoryextraction.episode

import com.homeassistant.application.port.inbound.identity.UserAccessDeniedException
import com.homeassistant.application.port.inbound.memoryextraction.DuplicateSourceRecordsException
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisJobReport
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisSubmissionResult
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisUnavailableException
import com.homeassistant.application.port.inbound.memoryextraction.InvalidAllowedUsersException
import com.homeassistant.application.port.inbound.memoryextraction.SourceAccessConflictException
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeAnalysisJob
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeAnalysisJobQueue
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeAnalysisSubmission
import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceReference
import java.time.Clock

/** Stores submitted source records and their reference, queues their analysis and reports it back to the submitter. */
class EpisodeAnalysisService(
    private val sourceRecords: SourceRecordRepository,
    private val jobs: EpisodeAnalysisJobQueue,
    private val accessPolicy: UserAccessPolicy,
    private val clock: Clock = Clock.systemUTC(),
) : EpisodeAnalysis {
    override fun submit(request: EpisodeAnalysisRequest): EpisodeAnalysisSubmissionResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        requireAuthorizedAudience(request.access.allowedUserIds)
        val saved = saveSourceRecords(request.source, request.access)
        requireSomethingToAnalyze(request.source, saved)
        val reference = request.source.reference?.let { reference ->
            rethrowAsUnavailable { sourceRecords.saveReference(reference) }
        }
        val job = enqueue(userId, request, saved, reference)
        return EpisodeAnalysisSubmissionResult(
            jobId = job.id,
            importedRecordCount = saved.importedRecordCount,
            retriedRecordCount = saved.retriedRecordCount,
            alreadyAnalyzedRecordCount = saved.alreadyAnalyzedRecordCount,
            visibility = request.access.visibility,
            allowedUserIds = request.access.allowedUserIds,
        )
    }

    override fun findJob(userId: String, jobId: Int): EpisodeAnalysisJobReport? {
        val requester = UserId(userId)
        return rethrowAsUnavailable { jobs.findRequestedBy(jobId, requester) }?.toReport()
    }

    /** Saves [source]'s records, reporting an access conflict apart from an unavailable store. */
    private fun saveSourceRecords(source: SourceDocumentDraft, access: AccessControl): SourceRecordSaveResult.Saved =
        when (val result = rethrowAsUnavailable { sourceRecords.save(source.source, source.records, access) }) {
            is SourceRecordSaveResult.Saved -> result
            is SourceRecordSaveResult.AccessConflict ->
                throw SourceAccessConflictException(source.source.name, result.existingAccess)
        }

    /** Rejects a submission whose every record was analyzed before and that brings no reference to interpret. */
    private fun requireSomethingToAnalyze(source: SourceDocumentDraft, saved: SourceRecordSaveResult.Saved) {
        if (saved.recordsToAnalyze.isEmpty() && source.records.isNotEmpty() && source.reference == null) {
            throw DuplicateSourceRecordsException(source.source.name, saved.alreadyAnalyzedRecordCount)
        }
    }

    /** Queues the analysis of [saved]'s records and the stored [reference] for [requestedBy]. */
    private fun enqueue(
        requestedBy: UserId,
        request: EpisodeAnalysisRequest,
        saved: SourceRecordSaveResult.Saved,
        reference: SourceReference?,
    ): EpisodeAnalysisJob {
        val submission = EpisodeAnalysisSubmission(
            requestedBy = requestedBy,
            source = request.source.source,
            sourceRecordIds = saved.recordsToAnalyze.map { it.id },
            referenceId = reference?.id,
            access = request.access,
        )
        return rethrowAsUnavailable { jobs.enqueue(submission, clock.millis()) }
    }

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
    }

    private fun requireAuthorizedAudience(userIds: Set<String>) {
        val invalid = userIds.filterTo(linkedSetOf()) { raw ->
            runCatching { UserId(raw) }.getOrNull()?.let(accessPolicy::isAuthorized) != true
        }
        if (invalid.isNotEmpty()) throw InvalidAllowedUsersException(invalid)
    }

    private fun EpisodeAnalysisJob.toReport() = EpisodeAnalysisJobReport(id, status, episodeCount, lastError)

    /** The result of [operation], rethrowing its failure as [EpisodeAnalysisUnavailableException]. */
    private fun <T> rethrowAsUnavailable(operation: () -> T): T =
        try {
            operation()
        } catch (error: Exception) {
            throw EpisodeAnalysisUnavailableException(error)
        }
}
