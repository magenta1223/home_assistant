package com.homeassistant.application.memory.io

import com.homeassistant.application.memory.MemoryContext
import com.homeassistant.domain.identity.UserId

/** Reads canonical-memory contexts for internal indexing and authorized retrieval. */
interface MemoryReader {
    /** Loads canonical-memory contexts for the supplied memory IDs. */
    fun findByIds(memoryIds: Collection<Int>): List<MemoryContext>

    /** Loads only contexts visible to the requesting user. */
    fun findVisibleByIds(userId: UserId, memoryIds: Collection<Int>): List<MemoryContext>
}
