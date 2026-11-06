package com.homeassistant.application.port.outbound.memoryextraction.episode

import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.identity.UserId

/** One completed source analysis: the episodes [createdBy] proposed from the [analyzedSourceRecordIds]. */
data class EpisodeBatch(
    val createdBy: UserId,
    val proposals: List<EpisodeProposal>,
    val analyzedSourceRecordIds: Collection<Int>,
)

/** Stored canonical episodes. Every condition is applied by the storage, so no caller filters what it has already read. */
interface EpisodeRepository {
    /** The episodes [userId] can read, oldest first. */
    fun findVisibleTo(userId: UserId): List<Episode>

    /**
     * Atomically commits [batch] and returns its canonical episodes.
     *
     * Implementations must persist episodes, evidence, indexing and Component-assignment outbox entries, and the
     * source-record status change in one transaction. External indexing must not happen inside this call.
     */
    fun save(batch: EpisodeBatch): List<Episode>
}
