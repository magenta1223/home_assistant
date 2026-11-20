package com.homeassistant.domain.memory.xmemory.episode

/** A source-backed atomic episode candidate produced by analysis. */
data class EpisodeProposal(
    val title: String,
    val content: String,
    val evidenceIds: List<Int>,
)
