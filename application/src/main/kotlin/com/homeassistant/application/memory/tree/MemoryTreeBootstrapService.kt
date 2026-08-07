package com.homeassistant.application.memory.tree

import com.homeassistant.application.memory.read.MemoryReader
import com.homeassistant.domain.identity.UserId

/** Explicit one-time bootstrap for existing flat root memories. */
class MemoryTreeBootstrapService(
    private val memories: MemoryReader,
    private val placement: MemoryPlacement,
) {
    suspend fun placeExistingRoots(userId: UserId, limit: Int = 10_000): Int {
        val roots = memories.getMemories(userId)
        placement.place(userId, roots)
        return roots.size
    }
}
