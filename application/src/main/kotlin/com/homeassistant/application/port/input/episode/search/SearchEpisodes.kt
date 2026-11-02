package com.homeassistant.application.port.input.episode.search

import com.homeassistant.domain.identity.UserId

/** Finds the readable episodes nearest a question, as xMemory retrieves episodes beside components. */
fun interface SearchEpisodes {
    /**
     * Up to the configured number of episodes [userId] can read, nearest [query] first; empty when none is readable.
     * @throws com.homeassistant.domain.identity.UserAccessDeniedException when [userId] may not search.
     */
    fun search(userId: UserId, query: String): List<EpisodeMatch>
}

data class EpisodeMatch(val episodeId: Int, val score: Double, val content: String, val createdAt: Long)
