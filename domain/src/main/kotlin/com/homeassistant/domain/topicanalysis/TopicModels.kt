package com.homeassistant.domain.topicanalysis

import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import kotlinx.serialization.Serializable

@Serializable
data class Topic(
    val id: Int,
    val createdByUserId: String,
    val sourceType: String,
    val sourceName: String,
    val title: String,
    val summary: String,
    val categories: List<String>,
    val memories: List<Memory>,
) {
    val memoryTypes: List<MemoryType> get() = memories.map { it.memoryType }.distinct()
    val evidenceRefs: List<Int> get() = memories.flatMap { it.evidenceRefs }.distinct()
}

@Serializable
data class ProposedMemory(
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: MemoryCertainty,
    val evidenceRefs: List<Int>,
    val visibility: MemoryVisibility = MemoryVisibility.FAMILY,
)

@Serializable
data class ProposedTopic(
    val createdByUserId: String,
    val sourceType: String,
    val sourceName: String,
    val title: String,
    val summary: String,
    val memoryTypes: List<MemoryType>,
    val categories: List<String>,
    val evidenceRefs: List<Int>,
    val memories: List<ProposedMemory>,
)
