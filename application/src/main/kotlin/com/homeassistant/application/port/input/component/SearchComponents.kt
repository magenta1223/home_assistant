package com.homeassistant.application.port.input.component

import com.homeassistant.domain.identity.UserId

/** Finds the Components that best cover a question, with only the episodes the requester can read. */
fun interface SearchComponents {
    /**
     * The Components covering [query] for [userId] in backbone pick order; empty when no Component exists or none is readable.
     * @throws com.homeassistant.domain.identity.UserAccessDeniedException when [userId] may not search.
     */
    fun search(userId: UserId, query: String): List<ComponentMatch>
}

/**
 * [episodes] are most relevant to the question first and never empty; an episode appears under one match only, and a
 * Component with nothing readable left is not a match.
 */
data class ComponentMatch(val componentId: Int, val score: Double, val episodes: List<ComponentMatchEpisode>) {
    init { require(episodes.isNotEmpty()) }
}

data class ComponentMatchEpisode(val episodeId: Int, val content: String, val createdAt: Long)
