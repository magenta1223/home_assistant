package com.homeassistant.application.memory.read

import kotlinx.serialization.Serializable


data class SearchMemoriesRequest(
    val userId: String,
    val query: String,
    val limit: Int = 5,
)

data class SearchMemoriesResult(
    val query: String,
    val matches: List<MemorySearchMatch>,
)

@Serializable
data class MemorySearchMatch(
    val memoryId: Int,
    val content: String,
    val evidenceRefs: List<Int>,
)