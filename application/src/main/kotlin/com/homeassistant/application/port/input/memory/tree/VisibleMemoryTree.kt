package com.homeassistant.application.port.input.memory.tree

import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType

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
    val subject: String,
    val content: String,
    val memoryType: MemoryType,
    val certainty: MemoryCertainty,
    val createdAt: Long,
    val children: List<VisibleMemoryTreeNode>,
)

class VisibleMemoryTreeUnavailableException internal constructor(
    cause: Throwable,
) : RuntimeException("visible memory tree is unavailable", cause)
