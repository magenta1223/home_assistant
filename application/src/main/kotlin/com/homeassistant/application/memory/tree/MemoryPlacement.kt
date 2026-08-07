package com.homeassistant.application.memory.tree

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory

/** Places newly-created flat memories into the existing memory tree. */
fun interface MemoryPlacement {
    suspend fun place(userId: UserId, memories: List<Memory>)

    object NoOpMemoryPlacement : MemoryPlacement {
        override suspend fun place(userId: UserId, memories: List<Memory>) = Unit
    }
}