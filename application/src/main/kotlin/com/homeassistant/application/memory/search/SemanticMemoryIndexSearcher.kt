package com.homeassistant.application.memory.search

data class SemanticMemorySearchHit(
    val memoryId: Int,
    val score: Double,
)

/** Searches the semantic memory index and returns ranked memory identifiers. */
fun interface SemanticMemoryIndexSearcher {
    /** Searches the semantic memory index and returns the best matching memory IDs. */
    fun search(query: String, limit: Int): List<SemanticMemorySearchHit>
}

class MemorySearchUnavailableException(message: String) : RuntimeException(message)
