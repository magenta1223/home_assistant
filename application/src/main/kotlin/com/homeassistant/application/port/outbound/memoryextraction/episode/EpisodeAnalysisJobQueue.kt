package com.homeassistant.application.port.outbound.memoryextraction.episode

import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.episode.EpisodeAnalysisJobStatus
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor

/**
 * A source analysis [requestedBy] asked for: the stored [sourceRecordIds] of [source] and the stored reference to
 * interpret, whose records are readable as [access].
 */
data class EpisodeAnalysisSubmission(
    val requestedBy: UserId,
    val source: SourceDescriptor,
    val sourceRecordIds: List<Int>,
    val referenceId: Int?,
    val access: AccessControl,
)

/** A stored [submission] and how far its analysis has come. */
data class EpisodeAnalysisJob(
    val id: Int,
    val submission: EpisodeAnalysisSubmission,
    val status: EpisodeAnalysisJobStatus,
    /** Monotonically increasing claim generation used to fence stale workers. */
    val attempt: Int,
    /** How many episodes the completed analysis produced; null until the job completes. */
    val episodeCount: Int?,
    /** Why the latest attempt failed; null when no attempt failed or the job completed. */
    val lastError: String?,
)

/** Durable queue of submitted source analyses. Every condition is applied by the storage, so no caller filters what it has already read. */
interface EpisodeAnalysisJobQueue {
    /** Stores [submission] as a pending job and returns it. */
    fun enqueue(submission: EpisodeAnalysisSubmission, now: Long): EpisodeAnalysisJob

    /**
     * Claims the oldest ready job and increments its attempt count, or returns null when none is ready.
     * Stale in-progress work may be reclaimed; failed work stays failed after [maxAttempts].
     */
    fun claimNext(now: Long, retryBefore: Long, staleProcessingBefore: Long, maxAttempts: Int): EpisodeAnalysisJob?

    /** Records the [episodeCount] of the analysis; returns true only when the matching in-progress generation was transitioned. */
    fun markCompleted(jobId: Int, expectedAttempt: Int, episodeCount: Int, now: Long): Boolean

    /** Returns true only when the matching in-progress generation was transitioned. */
    fun markFailed(jobId: Int, expectedAttempt: Int, error: String, now: Long): Boolean

    /** The job [jobId] when [userId] submitted it, otherwise null. */
    fun findRequestedBy(jobId: Int, userId: UserId): EpisodeAnalysisJob?
}
