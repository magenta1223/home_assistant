package com.homeassistant.domain.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A source-backed atomic memory candidate produced by analysis. */
@Serializable
data class MemoryProposal(
    @SerialName("text")
    val content: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: MemoryCertainty,
    @SerialName("evidenceRefs")
    val evidenceIds: List<Int>,
    val visibility: MemoryVisibility = MemoryVisibility.PUBLIC,
)
