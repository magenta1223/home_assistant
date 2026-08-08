package com.homeassistant.application.memory.tree

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory

class MemoryPlaceRequest(
    val userId: UserId,
    memories: List<Memory>,
) {
    val memories: List<Memory> = memories.toList()

    init {
        require(this.memories.distinctBy { it.id }.size == this.memories.size) {
            "Placement request contains duplicate memory ids"
        }
    }
}
