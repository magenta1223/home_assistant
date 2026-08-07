package com.homeassistant.application.memory.io

data class MemoryIndex(
    val memoryId: Int,
    val score: Double,
)

/** Searches the semantic memory index and returns ranked memory identifiers. */
fun interface SemanticMemoryIndexSearcher {
    /** Searches the semantic memory index and returns the best matching memory IDs. */
    fun search(query: String, limit: Int): List<MemoryIndex>
}

class MemorySearchUnavailableException(message: String) : RuntimeException(message)
