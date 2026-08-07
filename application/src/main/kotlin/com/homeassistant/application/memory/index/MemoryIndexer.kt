package com.homeassistant.application.memory.index

import com.homeassistant.application.memory.CanonicalMemoryContext

/** Adds or updates a canonical memory in the semantic index. */
fun interface MemoryIndexer {
    /** Adds or updates one canonical memory in the semantic index. */
    fun index(context: CanonicalMemoryContext)
}

/** Loads canonical-memory context needed to build index entries. */
fun interface MemoryIndexingSource {
    /** Loads the canonical-memory contexts for indexing. */
    fun findByIds(memoryIds: Collection<Int>): List<CanonicalMemoryContext>
}
