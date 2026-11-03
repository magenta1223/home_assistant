package com.homeassistant.application.usecase.episode.write

import com.homeassistant.application.port.output.episode.write.EpisodeWriter
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeProposal

/** Persists flat episode proposals immediately without a review stage. */
class EpisodeProposalsPersister(
    private val batchWriter: EpisodeWriter,
) {
    fun persist(
        userId: UserId,
        proposals: List<EpisodeProposal>,
        analyzedSourceRecordIds: Collection<Int>,
    ): List<Episode> {
        return batchWriter.commit(userId, proposals, analyzedSourceRecordIds)
    }
}