package com.homeassistant.application.memory.read

import kotlinx.serialization.Serializable


data class SearchMemoriesRequest(
    val userId: String,
    val query: String,
    val limit: Int = 5,
) {
    init {
        require(limit in MIN_LIMIT..MAX_LIMIT) { "memory search limit must be between $MIN_LIMIT and $MAX_LIMIT" }
    }

    companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 10
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
    val createdAt: Long,
    val source: MemorySearchMatchSource = MemorySearchMatchSource.DIRECT,
    val parentMemoryId: Int? = null,
    val depth: Int = 0,
)

@Serializable
enum class MemorySearchMatchSource {
    DIRECT,
    CHILD,
}
