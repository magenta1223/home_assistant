package com.homeassistant.application.topicanalysis.save

enum class IndexTargetType {
    MEMORY,
}

/** Tracks memory-indexing work that is pending or needs to be retried. */
interface IndexingOutboxStore {
    /** Returns pending target identifiers up to the requested limit. */
    fun pending(targetType: IndexTargetType, limit: Int = 100): List<Int>

    /** Marks one target as successfully indexed. */
    fun markIndexed(targetType: IndexTargetType, targetId: Int)

    /** Records that indexing one target failed and may need a retry. */
    fun markFailed(targetType: IndexTargetType, targetId: Int, error: String)
}
