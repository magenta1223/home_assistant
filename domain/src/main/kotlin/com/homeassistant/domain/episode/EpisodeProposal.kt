package com.homeassistant.domain.episode

/** A source-backed atomic episode candidate produced by analysis. */
data class EpisodeProposal(
    val title: String,
    val content: String,
    val evidenceIds: List<Int>,
)
