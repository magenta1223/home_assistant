package com.homeassistant.application.memory.io

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
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

/** Searches the caller-visible canonical memories and returns ranked matches. */
fun interface SearchMemoriesUseCase {
    /** Searches memories visible to the requesting user. */
    fun search(request: SearchMemoriesRequest): SearchMemoriesResult
}

class SearchMemories(
    private val memories: MemoryReader,
    private val searcher: SemanticMemoryIndexSearcher,
    private val accessPolicy: HouseholdAccessPolicy,
) : SearchMemoriesUseCase {
    override fun search(request: SearchMemoriesRequest): SearchMemoriesResult {
        val userId = UserId(request.userId)
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
        val query = request.query.trim()
        val hits = searcher.search(query, request.limit.coerceIn(1, 10))
        val memoriesById = memories.findVisibleByIds(userId, hits.map { it.memoryId }).associateBy { it.id }
        val matches = hits.mapNotNull { hit ->
            memoriesById[hit.memoryId]?.let { memory ->
                MemorySearchMatch(
                    memoryId = memory.id,
                    content = memory.content,
                    evidenceRefs = memory.evidenceRefs,
                )
            }
        }
        return SearchMemoriesResult(query, matches)
    }
}
