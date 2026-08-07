package com.homeassistant.application.memory.index

import com.homeassistant.domain.memory.Memory

/** Writes canonical-memory representations to the semantic search index. */
fun interface SemanticMemoryIndexWriter {
    /** Adds or updates one canonical memory in the semantic search index. */
    fun upsert(memory: Memory)
}
