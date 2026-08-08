package com.homeassistant.application.memory.read

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId


class MemorySearcher(
    private val memories: MemoryReader,
    private val searcher: SemanticMemoryIndexSearcher,
    private val accessPolicy: HouseholdAccessPolicy,
) {
    fun search(request: SearchMemoriesRequest): SearchMemoriesResult {
        val userId = UserId(request.userId)
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
        val query = request.query.trim()

        val memoriesById = memories.getMemories(userId).associateBy { it.id }
        val retrievedMemoryIndices = semanticSearch(query, request.limit, memoriesById.keys)
        val matches = retrievedMemoryIndices
            .distinctBy { it.memoryId }
            .mapNotNull { index ->
                memoriesById[index.memoryId]?.let { memory ->
                    MemorySearchMatch(
                        memoryId = memory.id,
                        content = memory.content,
                        evidenceRefs = memory.evidenceRefs,
                        score = index.score,
                    )
                }
            }
            .take(request.limit)
        return SearchMemoriesResult(query, matches)
    }

    private fun semanticSearch(query: String, limit: Int, allowedMemoryIds: Set<Int>): List<MemoryIndex> {
        if (allowedMemoryIds.isEmpty()) return emptyList()

        return searcher.search(
            query = query,
            limit = limit,
            scope = MemoryIndexSearchScope(
                allowedMemoryIds = allowedMemoryIds,
            ),
        )
    }
}
