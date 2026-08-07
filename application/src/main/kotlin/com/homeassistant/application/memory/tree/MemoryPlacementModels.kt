package com.homeassistant.application.memory.tree

import com.homeassistant.domain.memory.Memory

enum class MemoryPlacementDecisionType {
    EXISTING_PARENT,
    ROOT,
}

data class MemoryPlacementInput(
    val memory: Memory,
    val candidates: List<Memory>,
)

data class MemoryPlacementDecision(
    val memoryId: Int,
    val decision: MemoryPlacementDecisionType,
    val containerId: Int? = null,
)

data class MemoryPlacementBatchResult(
    val decisions: List<MemoryPlacementDecision>,
)

/** Batch-capable boundary for a Codex-backed placement implementation. */
fun interface MemoryPlacementExtractor {
    suspend fun analyze(inputs: List<MemoryPlacementInput>): MemoryPlacementBatchResult
}

class MemoryPlacementException(message: String) : RuntimeException(message)
