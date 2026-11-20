package com.homeassistant.application.port.outbound.memory.xmemory.episode

import com.homeassistant.domain.memory.xmemory.episode.Episode
import com.homeassistant.domain.memory.xmemory.episode.EpisodeProposal
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
     * Implementations must persist episodes, evidence, indexing and Component-assignment outbox entries, the
     * source-record status change, and the removal of the analyzed records from the [EpisodeExtractionQueue] in one
     * transaction. External indexing must not happen inside this call.
     */
    fun save(batch: EpisodeBatch): List<Episode>
}
