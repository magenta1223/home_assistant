package com.homeassistant.application.port.output.episode.write

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeProposal

/** One proposed episode together with its stable retry identity. */
data class IdempotentEpisodeProposal(
    val idempotencyKey: String,
    val proposal: EpisodeProposal,
)

/**
 * Atomically commits one completed source-analysis batch.
 *
 * Implementations must persist episodes, evidence, indexing and Component-assignment outbox entries, and the
 * source-record status change in one transaction. External indexing must not happen inside this call.
 */
fun interface CanonicalEpisodeBatchWriter {
    fun commit(
        createdBy: UserId,
        proposals: List<IdempotentEpisodeProposal>,
        analyzedSourceRecordIds: Collection<Int>,
    ): List<Episode>
}
