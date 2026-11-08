package com.homeassistant.application.port.inbound.memoryextraction

import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.access.Visibility
import com.homeassistant.domain.episode.EpisodeAnalysisJobStatus
import com.homeassistant.domain.source.SourceDocumentDraft

data class EpisodeAnalysisRequest(
    val userId: String,
    val source: SourceDocumentDraft,
    val access: AccessControl = AccessControl.PUBLIC,
)

/** The queued analysis [jobId] and what happened to the submitted records. */
data class EpisodeAnalysisSubmissionResult(
    val jobId: Int,
    val importedRecordCount: Int,
    val retriedRecordCount: Int,
    val alreadyAnalyzedRecordCount: Int,
    val visibility: Visibility,
    val allowedUserIds: Set<String>,
)

/** How the submitted analysis [jobId] is going. */
data class EpisodeAnalysisJobReport(
    val jobId: Int,
    val status: EpisodeAnalysisJobStatus,
    /** How many episodes the analysis saved; null until it completes. */
    val episodeCount: Int?,
    /** Why the latest attempt failed; null when no attempt failed or the analysis completed. */
    val lastError: String?,
)

class DuplicateSourceRecordsException internal constructor(
    val sourceName: String,
    val recordCount: Int,
) : RuntimeException("All $recordCount source records already exist: $sourceName")

class EpisodeAnalysisUnavailableException internal constructor(
    cause: Throwable,
) : RuntimeException("episode analysis is unavailable", cause)

class InvalidAllowedUsersException internal constructor(
    val userIds: Set<String>,
) : RuntimeException("allowed users contain unknown or unauthorized users")

class SourceAccessConflictException internal constructor(
    val sourceName: String,
    val existingAccess: AccessControl,
) : RuntimeException("source already exists with a different audience: $sourceName")
