package com.homeassistant.application.memory.tree

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory

data class MemoryPlaceRequest(
    val userId: UserId,
    val memories: List<Memory>
)
