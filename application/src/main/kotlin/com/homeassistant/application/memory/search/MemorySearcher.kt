package com.homeassistant.application.memory.search

import com.homeassistant.application.memory.CanonicalMemoryContext
import com.homeassistant.domain.identity.UserId

data class MemorySearchHit(
    val memoryId: Int,
    val score: Double,
)

/** Searches the semantic index and returns ranked memory identifiers. */
fun interface MemorySearcher {
    /** Searches the semantic index and returns the best matching memory IDs. */
    fun search(query: String, limit: Int): List<MemorySearchHit>
}

/** Reads visible canonical memories and their optional topic context. */
fun interface CanonicalMemoryReader {
    /** Loads visible memory contexts for the supplied memory IDs. */
    fun findVisibleByIds(userId: UserId, memoryIds: Collection<Int>): List<CanonicalMemoryContext>
}

class MemorySearchUnavailableException(message: String) : RuntimeException(message)
