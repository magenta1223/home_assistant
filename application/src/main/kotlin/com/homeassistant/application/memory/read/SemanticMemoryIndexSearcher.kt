package com.homeassistant.application.memory.read

data class MemoryIndex(
    val memoryId: Int,
    val score: Double,
)

data class MemoryIndexSearchScope(
    val allowedMemoryIds: Set<Int>? = null,
)

/** Searches the semantic memory index and returns ranked memory identifiers. */
fun interface SemanticMemoryIndexSearcher {
    /** Searches the semantic memory index and returns the best matching memory IDs. */
    fun search(query: String, limit: Int): List<MemoryIndex>

    /** Searches a bounded part of the tree; implementations may preserve the flat fallback. */
    fun search(query: String, limit: Int, scope: MemoryIndexSearchScope): List<MemoryIndex> =
        search(query, limit)
}

class MemorySearchUnavailableException(message: String) : RuntimeException(message)
