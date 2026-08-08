package com.homeassistant.application.memory.tree

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory

data class MemoryTreeAttachRequest(
    val userId: UserId,
    val parentByChild: Map<Int, Int>,
)

data class MemoryTreeAttachResponse(
    val updatedMemories: List<Memory>,
)

/** Mutates the single-parent memory tree without introducing relation types. */
interface MemoryTreeStore {
    /** Applies all direct-parent assignments atomically for one placement batch. */
    fun attachChildren(request: MemoryTreeAttachRequest): MemoryTreeAttachResponse
}
