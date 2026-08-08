package com.homeassistant.application.memory.tree

import com.homeassistant.domain.memory.Memory

/** All context needed to place one saved batch in a single Codex call. */
data class MemoryPlacementInput(
    val memories: List<Memory>,
    val visibleMemoryTree: String,
)

data class MemoryPlacementDecision(
    val memoryId: Int,
    /** null keeps the memory at the root; otherwise this is the direct parent id. */
    val parentId: Int?,
)

data class MemoryPlacementResponse(
    val decisions: List<MemoryPlacementDecision>,
)

/** Batch-capable boundary for a Codex-backed placement implementation. */
fun interface MemoryPlacementExtractor {
    suspend fun analyze(input: MemoryPlacementInput): MemoryPlacementResponse
}

class MemoryPlacementException(message: String) : RuntimeException(message)
