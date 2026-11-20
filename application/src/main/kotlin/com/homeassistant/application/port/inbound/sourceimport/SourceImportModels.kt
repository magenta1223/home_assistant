package com.homeassistant.application.port.inbound.sourceimport

import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.access.Visibility
import com.homeassistant.domain.source.SourceImportJobStatus
import com.homeassistant.domain.source.SourceDocumentDraft

data class SourceImportRequest(
    val userId: String,
    val source: SourceDocumentDraft,
    val access: AccessControl = AccessControl.PUBLIC,
)

/** The queued import job [jobId] and what happened to the submitted records. */
data class SourceImportSubmissionResult(
    val jobId: Int,
    /** How many submitted records were stored now. */
    val importedRecordCount: Int,
    /** How many submitted records were stored before under the same deduplication key. */
    val alreadyStoredRecordCount: Int,
    val visibility: Visibility,
    val allowedUserIds: Set<String>,
)

/** How the submitted import job [jobId] is going. */
data class SourceImportJobReport(
    val jobId: Int,
    val status: SourceImportJobStatus,
    /** How many source records the job stored for memory updates; null until it completes. */
    val recordCount: Int?,
    /** Why the latest attempt failed; null when no attempt failed or the job completed. */
    val lastError: String?,
)

class DuplicateSourceRecordsException internal constructor(
    val sourceName: String,
    val recordCount: Int,
) : RuntimeException("All $recordCount source records were stored before: $sourceName")

class SourceImportUnavailableException internal constructor(
    cause: Throwable,
) : RuntimeException("episode analysis is unavailable", cause)

class InvalidAllowedUsersException internal constructor(
    val userIds: Set<String>,
) : RuntimeException("allowed users contain unknown or unauthorized users")

class SourceAccessConflictException internal constructor(
    val sourceName: String,
    val existingAccess: AccessControl,
) : RuntimeException("source already exists with a different audience: $sourceName")
