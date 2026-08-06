package com.homeassistant.application.memory.index

import com.homeassistant.application.memory.CanonicalMemoryContext

fun interface MemoryIndexer {
    fun index(context: CanonicalMemoryContext)
}

fun interface MemoryIndexingSource {
    fun findByIds(memoryIds: Collection<Int>): List<CanonicalMemoryContext>
}
