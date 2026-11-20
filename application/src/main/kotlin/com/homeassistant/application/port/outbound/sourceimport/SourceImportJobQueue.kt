package com.homeassistant.application.port.outbound.sourceimport

import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.source.SourceImportJobStatus
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor

/**
 * A source import [requestedBy] asked for: the newly stored [sourceRecordIds] of [source] and the stored reference to
 * interpret, whose records are readable as [access].
 */
data class SourceImportSubmission(
    val requestedBy: UserId,
    val source: SourceDescriptor,
    val sourceRecordIds: List<Int>,
    val referenceId: Int?,
    val access: AccessControl,
)

/** A stored [submission] and how far its import has come. */
data class SourceImportJob(
    val id: Int,
    val submission: SourceImportSubmission,
    val status: SourceImportJobStatus,
    /** Monotonically increasing claim generation used to fence stale workers. */
    val attempt: Int,
    /** How many source records the completed import covers; null until the job completes. */
    val recordCount: Int?,
    /** Why the latest attempt failed; null when no attempt failed or the job completed. */
    val lastError: String?,
)

/** Durable queue of submitted source imports. Every condition is applied by the storage, so no caller filters what it has already read. */
interface SourceImportJobQueue {
    /** Stores [submission] as a pending job and returns it. */
    fun enqueue(submission: SourceImportSubmission, now: Long): SourceImportJob

    /**
     * Claims the oldest ready job and increments its attempt count, or returns null when none is ready.
     * Stale in-progress work may be reclaimed; failed work stays failed after [maxAttempts].
     */
    fun claimNext(now: Long, retryBefore: Long, staleProcessingBefore: Long, maxAttempts: Int): SourceImportJob?

    /** Records the [recordCount] of the import; returns true only when the matching in-progress generation was transitioned. */
    fun markCompleted(jobId: Int, expectedAttempt: Int, recordCount: Int, now: Long): Boolean

    /** Returns true only when the matching in-progress generation was transitioned. */
    fun markFailed(jobId: Int, expectedAttempt: Int, error: String, now: Long): Boolean

    /** The job [jobId] when [userId] submitted it, otherwise null. */
    fun findRequestedBy(jobId: Int, userId: UserId): SourceImportJob?
}
