package com.homeassistant.application.port.output.memory.placement

import com.homeassistant.domain.identity.UserId

data class MemoryTreeAttachRequest(
    val userId: UserId,
    val parentByChild: Map<Int, Int>,
)

/** Mutates the single-parent memory tree without introducing relation types. */
interface MemoryTreeStore {
    /** Applies all direct-parent assignments atomically for one placement batch. */
    fun attachChildren(request: MemoryTreeAttachRequest)
}
