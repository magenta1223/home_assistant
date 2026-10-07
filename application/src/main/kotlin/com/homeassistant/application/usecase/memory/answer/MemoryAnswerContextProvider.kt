package com.homeassistant.application.usecase.memory.answer

import com.homeassistant.application.port.input.memory.search.MemorySearch
import com.homeassistant.application.port.input.memory.search.MemorySearchMatch
import com.homeassistant.application.port.input.memory.search.SearchMemoriesRequest

/** Preserves authorized direct matches while Topic-based retrieval is a separate follow-up. */
class MemoryAnswerContextProvider(
    private val memorySearcher: MemorySearch,
) {
    fun context(request: SearchMemoriesRequest): MemoryAnswerContext {
        val result = memorySearcher.search(request)
        return MemoryAnswerContext(result.query, result.matches, result.matches)
    }
}

data class MemoryAnswerContext(
    val query: String,
    val directMatches: List<MemorySearchMatch>,
    val contextMatches: List<MemorySearchMatch>,
)
