package com.homeassistant.domain.topicanalysis

import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryType
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
