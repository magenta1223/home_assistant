package com.homeassistant.application.memory.save

enum class IndexTargetType {
    MEMORY,
}

/** Tracks pending or failed memory-indexing work. */
interface IndexingOutboxStore {
    fun pending(targetType: IndexTargetType, limit: Int = 100): List<Int>
    fun markIndexed(targetType: IndexTargetType, targetId: Int)
    fun markFailed(targetType: IndexTargetType, targetId: Int, error: String)
}
