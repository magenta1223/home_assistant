package com.homeassistant.domain.topic

import com.homeassistant.domain.memory.MemoryAccess

/**
 * The part of a Topic one viewer can read: the PUBLIC slice and the slices that list the viewer.
 * Viewers who read the same slices share a view. A slice only gains memories, so its size is its version.
 */
data class TopicView(val topicId: Int, val slices: Map<MemoryAccess, List<Int>>) {
    init {
        require(topicId > 0) { "topic id must be positive" }
        require(slices.values.all { it.isNotEmpty() }) { "topic slice must contain memories" }
    }

    /** True when the viewer can read none of the Topic. */
    val isHidden: Boolean get() = slices.isEmpty()

    val key: TopicViewKey get() = TopicViewKey(topicId, slices.keys)

    val versions: Map<MemoryAccess, Int> get() = slices.mapValues { it.value.size }

    val memoryIds: List<Int> get() = slices.values.flatten()
}

/** A Topic seen through one set of slices. A permission change changes the set, so it never reuses another view's data. */
data class TopicViewKey(val topicId: Int, val slices: Set<MemoryAccess>) {
    init { require(topicId > 0 && slices.isNotEmpty()) }
}
