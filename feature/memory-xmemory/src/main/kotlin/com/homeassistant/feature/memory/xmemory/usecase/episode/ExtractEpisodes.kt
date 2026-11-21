package com.homeassistant.feature.memory.xmemory.usecase.episode

import com.homeassistant.feature.memory.xmemory.port.outbound.episode.EpisodeBatch
import com.homeassistant.feature.memory.xmemory.port.outbound.episode.EpisodeExtractionClaim
import com.homeassistant.feature.memory.xmemory.port.outbound.episode.EpisodeExtractor
import com.homeassistant.feature.memory.xmemory.port.outbound.episode.EpisodeRepository
import com.homeassistant.domain.memory.xmemory.episode.Episode

/** Extracts Episodes from the records of one claimed source and stores them as the claim's submitter's Episodes. */
class ExtractEpisodes(
    private val extractor: EpisodeExtractor,
    private val episodes: EpisodeRepository,
) {
    /**
     * The Episodes stored from [claim]'s records; empty when they held none. Storing them also takes every claimed
     * record off the extraction queue, including records no Episode cites.
     */
    suspend fun extract(claim: EpisodeExtractionClaim): List<Episode> {
        val proposals = extractor.analyze(claim.document)
        return episodes.save(EpisodeBatch(claim.submittedBy, proposals, claim.document.records.map { it.id }))
    }
}
