package com.homeassistant.feature.memory.xmemory.port.outbound.episode

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDocument

/**
 * The stored records of one source that [submittedBy] submitted and xMemory has not extracted Episodes from yet, in
 * recorded order. [claimId] fences the claim: once another claim takes the records, this one can no longer fail them.
 */
data class EpisodeExtractionClaim(
    val claimId: String,
    val submittedBy: UserId,
    val document: SourceDocument,
)

/**
 * Stored source records waiting for xMemory to extract Episodes from them. A record leaves the queue when
 * [EpisodeRepository.save] stores the Episodes extracted from it. Every condition is applied by the storage, so no
 * caller filters what it has already read.
 */
interface EpisodeExtractionQueue {
    /**
     * Claims every ready record of the source and submitter whose ready record was recorded first, and increments each
     * record's attempt count; returns null when no record is ready. A record is ready when it was never claimed, when
     * its claim failed at or before [retryBefore] with fewer than [maxAttempts] attempts, or when it has been claimed
     * since [staleProcessingBefore] or earlier.
     */
    fun claimNext(now: Long, retryBefore: Long, staleProcessingBefore: Long, maxAttempts: Int): EpisodeExtractionClaim?

    /** Records [error] on the records of claim [claimId]; returns true only when that claim still held them. */
    fun markFailed(claimId: String, error: String, now: Long): Boolean
}
