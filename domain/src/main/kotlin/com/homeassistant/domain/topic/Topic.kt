package com.homeassistant.domain.topic

import com.homeassistant.domain.memory.MemoryAccess

/**
 * xMemory component: one centroid over its memories, which it lists by each memory's access.
 * Membership never changes a memory or its access.
 */
data class Topic(
    val id: Int,
    val centroid: TopicCentroid,
    val slices: Map<MemoryAccess, List<Int>>,
) {
    init {
        require(id > 0) { "topic id must be positive" }
        val memoryIds = slices.values.flatten()
        require(slices.values.all { it.isNotEmpty() }) { "topic slice must contain memories" }
        require(memoryIds.all { it > 0 }) { "memory ids must be positive" }
        require(memoryIds.distinct().size == memoryIds.size) { "a memory belongs to one slice" }
        require(memoryIds.size == centroid.count) { "centroid must cover every memory" }
    }
}
