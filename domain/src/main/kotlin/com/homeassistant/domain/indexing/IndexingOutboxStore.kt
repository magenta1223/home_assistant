package com.homeassistant.domain.indexing

enum class IndexTargetType {
    MEMORY,
    TOPIC,
}

interface IndexingOutboxStore {
    fun pending(targetType: IndexTargetType, limit: Int = 100): List<Int>
    fun markIndexed(targetType: IndexTargetType, targetId: Int)
    fun markFailed(targetType: IndexTargetType, targetId: Int, error: String)
}

private object NoOpIndexingOutboxStore : IndexingOutboxStore {
    override fun pending(targetType: IndexTargetType, limit: Int): List<Int> = emptyList()
    override fun markIndexed(targetType: IndexTargetType, targetId: Int) = Unit
    override fun markFailed(targetType: IndexTargetType, targetId: Int, error: String) = Unit
}

object IndexingOutboxes {
    fun noOp(): IndexingOutboxStore =
        NoOpIndexingOutboxStore
}
