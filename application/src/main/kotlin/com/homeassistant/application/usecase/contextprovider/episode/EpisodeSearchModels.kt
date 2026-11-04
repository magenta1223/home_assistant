package com.homeassistant.application.usecase.contextprovider.episode

data class EpisodeMatch(val episodeId: Int, val score: Double, val content: String, val createdAt: Long)