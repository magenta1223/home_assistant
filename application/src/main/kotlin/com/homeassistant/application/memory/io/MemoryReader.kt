package com.homeassistant.application.memory.io

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory

/** Reads canonical-memory contexts for internal indexing and authorized retrieval. */
interface MemoryReader {
    /** Loads canonical memories for the supplied memory IDs. */
    fun findByIds(memoryIds: Collection<Int>): List<Memory>

    /** Loads only contexts visible to the requesting user. */
    fun findVisibleByIds(userId: UserId, memoryIds: Collection<Int>): List<Memory>
}
