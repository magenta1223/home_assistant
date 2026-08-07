package com.homeassistant.domain.topicanalysis

import com.homeassistant.domain.memory.MemoryType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class TopicProposal(
    val title: String,
    val summary: String,
    val categories: List<String>,
    val memories: List<MemoryProposal>,
    val memoryTypes: List<MemoryType> = memories.map { it.memoryType }.distinct(),
    @SerialName("evidenceRefs")
    val evidenceIds: List<Int> = memories.flatMap { it.evidenceIds }.distinct(),
)
