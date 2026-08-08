package com.homeassistant.application.port.input.memory.placement

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory

/** Places newly-created flat memories into the existing memory tree. */
fun interface MemoryPlacement {
    suspend fun place(memoryPlaceRequest: MemoryPlaceRequest)

    object NoOpMemoryPlacement : MemoryPlacement {
        override suspend fun place(memoryPlaceRequest: MemoryPlaceRequest) = Unit
    }
}

class MemoryPlacementException internal constructor(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
