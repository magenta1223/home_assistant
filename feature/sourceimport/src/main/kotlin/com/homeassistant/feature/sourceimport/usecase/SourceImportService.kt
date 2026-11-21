package com.homeassistant.feature.sourceimport.usecase

import com.homeassistant.application.port.inbound.identity.UserAccessDeniedException
import com.homeassistant.application.port.inbound.sourceimport.DuplicateSourceRecordsException
import com.homeassistant.application.port.inbound.sourceimport.SourceImport
import com.homeassistant.application.port.inbound.sourceimport.SourceImportJobReport
import com.homeassistant.application.port.inbound.sourceimport.SourceImportRequest
import com.homeassistant.application.port.inbound.sourceimport.SourceImportSubmissionResult
import com.homeassistant.application.port.inbound.sourceimport.SourceImportUnavailableException
import com.homeassistant.application.port.inbound.sourceimport.InvalidAllowedUsersException
import com.homeassistant.application.port.inbound.sourceimport.SourceAccessConflictException
import com.homeassistant.feature.sourceimport.port.outbound.SourceImportJob
import com.homeassistant.feature.sourceimport.port.outbound.SourceImportJobQueue
import com.homeassistant.feature.sourceimport.port.outbound.SourceImportSubmission
import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceReference
import java.time.Clock

/** Stores submitted source records and their reference, queues their import job and reports it back to the submitter. */
class SourceImportService(
    private val sourceRecords: SourceRecordRepository,
    private val jobs: SourceImportJobQueue,
    private val accessPolicy: UserAccessPolicy,
    private val clock: Clock = Clock.systemUTC(),
) : SourceImport {
    override fun submit(request: SourceImportRequest): SourceImportSubmissionResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        requireAuthorizedAudience(request.access.allowedUserIds)
        val saved = saveSourceRecords(request.source, userId, request.access)
        requireSomethingNew(request.source, saved)
        val reference = request.source.reference?.let { reference ->
            rethrowAsUnavailable { sourceRecords.saveReference(reference) }
        }
        val job = enqueue(userId, request, saved, reference)
        return SourceImportSubmissionResult(
            jobId = job.id,
            importedRecordCount = saved.newRecords.size,
            alreadyStoredRecordCount = saved.alreadyStoredRecords.size,
            visibility = request.access.visibility,
            allowedUserIds = request.access.allowedUserIds,
        )
    }

    override fun findJob(userId: String, jobId: Int): SourceImportJobReport? {
        val requester = UserId(userId)
        return rethrowAsUnavailable { jobs.findRequestedBy(jobId, requester) }?.toReport()
    }

    /** Saves [source]'s records as [submittedBy]'s, reporting an access conflict apart from an unavailable store. */
    private fun saveSourceRecords(
        source: SourceDocumentDraft,
        submittedBy: UserId,
        access: AccessControl,
    ): SourceRecordSaveResult.Saved =
        when (val result = rethrowAsUnavailable { sourceRecords.save(source.source, source.records, submittedBy, access) }) {
            is SourceRecordSaveResult.Saved -> result
            is SourceRecordSaveResult.AccessConflict ->
                throw SourceAccessConflictException(source.source.name, result.existingAccess)
        }

    /** Rejects a submission whose every record was stored before and that brings no reference to interpret. */
    private fun requireSomethingNew(source: SourceDocumentDraft, saved: SourceRecordSaveResult.Saved) {
        if (saved.newRecords.isEmpty() && source.records.isNotEmpty() && source.reference == null) {
            throw DuplicateSourceRecordsException(source.source.name, saved.alreadyStoredRecords.size)
        }
    }

    /** Queues the import of [saved]'s new records and the stored [reference] for [requestedBy]. */
    private fun enqueue(
        requestedBy: UserId,
        request: SourceImportRequest,
        saved: SourceRecordSaveResult.Saved,
        reference: SourceReference?,
    ): SourceImportJob {
        val submission = SourceImportSubmission(
            requestedBy = requestedBy,
            source = request.source.source,
            sourceRecordIds = saved.newRecords.map { it.id },
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

    private fun SourceImportJob.toReport() = SourceImportJobReport(id, status, recordCount, lastError)

    /** The result of [operation], rethrowing its failure as [SourceImportUnavailableException]. */
    private fun <T> rethrowAsUnavailable(operation: () -> T): T =
        try {
            operation()
        } catch (error: Exception) {
            throw SourceImportUnavailableException(error)
        }
}
