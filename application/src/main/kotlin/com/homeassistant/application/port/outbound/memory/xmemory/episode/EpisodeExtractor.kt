package com.homeassistant.application.port.outbound.memory.xmemory.episode

import com.homeassistant.domain.memory.xmemory.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDocument

/** Converts a source document into flat, evidence-backed atomic episodes. */
interface EpisodeExtractor {
    /** Extracts atomic episode proposals from the supplied source document. */
    suspend fun analyze(document: SourceDocument): List<EpisodeProposal>
}