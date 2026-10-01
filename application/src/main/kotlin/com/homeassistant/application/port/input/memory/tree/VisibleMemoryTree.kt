package com.homeassistant.application.port.input.memory.tree

fun interface VisibleMemoryTree {
    fun get(request: VisibleMemoryTreeRequest): VisibleMemoryTreeResult
}

data class VisibleMemoryTreeRequest(
    val userId: String,
)

data class VisibleMemoryTreeResult(
    val roots: List<VisibleMemoryTreeNode>,
    val totalCount: Int,
)

data class VisibleMemoryTreeNode(
    val memoryId: Int,
    val content: String,
    val createdAt: Long,
    val children: List<VisibleMemoryTreeNode>,
)

class VisibleMemoryTreeUnavailableException internal constructor(
    cause: Throwable,
) : RuntimeException("visible memory tree is unavailable", cause)
