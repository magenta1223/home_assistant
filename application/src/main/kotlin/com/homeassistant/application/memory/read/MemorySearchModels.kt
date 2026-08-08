package com.homeassistant.application.memory.read

import kotlinx.serialization.Serializable


data class SearchMemoriesRequest(
    val userId: String,
    val query: String,
    val limit: Int = 5,
) {
    init {
        require(limit in 1..MAX_SEARCH_LIMIT) { "memory search limit must be between 1 and $MAX_SEARCH_LIMIT" }
    }

    private companion object {
        const val MAX_SEARCH_LIMIT = 10
    }
}

data class SearchMemoriesResult(
    val query: String,
    val matches: List<MemorySearchMatch>,
)

@Serializable
data class MemorySearchMatch(
    val memoryId: Int,
    val content: String,
    val evidenceRefs: List<Int>,
    val score: Double,
)
