package com.homeassistant.application.usecase.contextprovider.component

/**
 * [episodes] are most relevant to the question first and never empty; an episode appears under one match only, and a
 * Component with nothing readable left is not a match.
 */
data class ComponentMatch(val componentId: Int, val score: Double, val episodes: List<ComponentMatchEpisode>) {
    init { require(episodes.isNotEmpty()) }
}

data class ComponentMatchEpisode(val episodeId: Int, val content: String, val createdAt: Long)
