package com.homeassistant.adapter.outbound.episodeanalysis

import com.homeassistant.application.port.outbound.episode.EpisodeExtractor
import com.homeassistant.llm.CompletionClient

object EpisodeExtractorFactory {
    fun create(client: CompletionClient): EpisodeExtractor = EpisodeExtractor(client)
}
