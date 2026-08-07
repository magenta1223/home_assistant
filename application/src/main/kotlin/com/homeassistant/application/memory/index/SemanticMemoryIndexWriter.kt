package com.homeassistant.application.memory.index

import com.homeassistant.application.memory.MemoryContext

/** Writes canonical-memory representations to the semantic search index. */
fun interface SemanticMemoryIndexWriter {
    /** Adds or updates one canonical memory in the semantic search index. */
    fun upsert(context: MemoryContext)
}
