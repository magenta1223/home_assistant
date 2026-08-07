package com.homeassistant.application.memory.read

import com.homeassistant.application.memory.CanonicalMemoryContext
import com.homeassistant.domain.identity.UserId

/** Reads canonical-memory contexts for internal indexing and authorized retrieval. */
interface CanonicalMemoryReader {
    /** Loads canonical-memory contexts for the supplied memory IDs. */
    fun findByIds(memoryIds: Collection<Int>): List<CanonicalMemoryContext>

    /** Loads only contexts visible to the requesting user. */
    fun findVisibleByIds(userId: UserId, memoryIds: Collection<Int>): List<CanonicalMemoryContext>
}
