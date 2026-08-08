package com.homeassistant.application.usecase.memory.placement

import com.homeassistant.application.port.input.memory.placement.MemoryPlaceRequest
import com.homeassistant.application.port.input.memory.placement.MemoryPlacement
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.domain.identity.UserId

/** Explicit one-time bootstrap for existing flat root memories. */
class MemoryTreeBootstrapService(
    private val memories: MemoryReader,
    private val placement: MemoryPlacement,
) {
    suspend fun placeExistingRoots(userId: UserId, limit: Int = 10_000): Int {
        val roots = memories.getMemories(userId).take(limit)
        placement.place(MemoryPlaceRequest(userId, roots))
        return roots.size
    }
}
