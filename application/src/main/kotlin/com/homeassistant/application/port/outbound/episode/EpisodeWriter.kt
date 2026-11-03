package com.homeassistant.application.port.outbound.episode

import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.identity.UserId

/**
 * Atomically commits one completed source-analysis batch.
 *
 * Implementations must persist episodes, evidence, indexing and Component-assignment outbox entries, and the
 * source-record status change in one transaction. External indexing must not happen inside this call.
 */
fun interface EpisodeWriter {
    fun commit(
        createdBy: UserId,
        proposals: List<EpisodeProposal>,
        analyzedSourceRecordIds: Collection<Int>,
    ): List<Episode>
}