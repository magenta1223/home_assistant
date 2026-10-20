package com.homeassistant.domain.memory

/** A source-backed atomic memory candidate produced by analysis. */
data class MemoryProposal(
    val content: String,
    val evidenceIds: List<Int>,
)
