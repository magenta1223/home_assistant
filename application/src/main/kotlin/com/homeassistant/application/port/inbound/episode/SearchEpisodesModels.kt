package com.homeassistant.application.port.inbound.episode

data class EpisodeMatch(val episodeId: Int, val score: Double, val content: String, val createdAt: Long)