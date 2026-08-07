package com.homeassistant.domain.topicanalysis

import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MemoryProposal(
    @SerialName("text")
    val content: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: MemoryCertainty,
    @SerialName("evidenceRefs")
    val evidenceIds: List<Int>,
    val visibility: MemoryVisibility = MemoryVisibility.FAMILY,
)
