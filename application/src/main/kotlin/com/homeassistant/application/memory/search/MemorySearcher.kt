package com.homeassistant.application.memory.search

import com.homeassistant.application.memory.CanonicalMemoryContext
import com.homeassistant.domain.identity.UserId

data class MemorySearchHit(
    val memoryId: Int,
    val score: Double,
)

fun interface MemorySearcher {
    fun search(query: String, limit: Int): List<MemorySearchHit>
}

fun interface CanonicalMemoryReader {
    fun findVisibleByIds(userId: UserId, memoryIds: Collection<Int>): List<CanonicalMemoryContext>
}

class MemorySearchUnavailableException(message: String) : RuntimeException(message)
