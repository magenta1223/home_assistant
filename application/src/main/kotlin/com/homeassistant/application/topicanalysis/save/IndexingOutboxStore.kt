package com.homeassistant.application.topicanalysis.save

enum class IndexTargetType {
    MEMORY,
}

interface IndexingOutboxStore {
    fun pending(targetType: IndexTargetType, limit: Int = 100): List<Int>
    fun markIndexed(targetType: IndexTargetType, targetId: Int)
    fun markFailed(targetType: IndexTargetType, targetId: Int, error: String)
}
