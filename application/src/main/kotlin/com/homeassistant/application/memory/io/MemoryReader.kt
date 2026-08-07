package com.homeassistant.application.memory.io

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory

/** Reads canonical-memory contexts for internal indexing and authorized retrieval. */
interface MemoryReader {
    /** Loads only contexts visible to the requesting user. */
    fun getMemories(userId: UserId): List<Memory>
}
