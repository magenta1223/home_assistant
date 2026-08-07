package com.homeassistant.application.memory.io

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory

/** Reads canonical-memory contexts for internal indexing and authorized retrieval. */
interface MemoryReader {
    /** Loads canonical memories for the supplied memory IDs. */
    fun findByIds(memoryIds: Collection<Int>): List<Memory>

    /** Loads only contexts visible to the requesting user. */
    fun findVisibleByIds(userId: UserId, memoryIds: Collection<Int>): List<Memory>

    /** Loads direct children of one tree node. */
    fun findChildren(containerId: Int): List<Memory> = emptyList()

    /** Loads root memories, optionally bounded for batch maintenance. */
    fun findRootMemories(limit: Int = 1000): List<Memory> = emptyList()

    /** Loads visible leaf memories only; structural parents are never answer evidence. */
    fun findVisibleLeafByIds(userId: UserId, memoryIds: Collection<Int>): List<Memory> =
        findVisibleByIds(userId, memoryIds).filter { it.visibility != com.homeassistant.domain.memory.MemoryVisibility.STRUCTURAL }
}
