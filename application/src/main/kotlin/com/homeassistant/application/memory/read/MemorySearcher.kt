package com.homeassistant.application.memory.read

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory


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
        val retrievedMemoryIndices = semanticSearch(userId, query, memoriesById)
        val matches = retrievedMemoryIndices.mapNotNull { memoryId ->
            memoriesById[memoryId]?.let { memory ->
                MemorySearchMatch(
                    memoryId = memory.id,
                    content = memory.content,
                    evidenceRefs = memory.evidenceRefs,
                )
            }
        }
        return SearchMemoriesResult(query, matches)
    }

    private fun semanticSearch(userId: UserId, query: String, memoriesById: Map<Int, Memory>): List<Int> {
        if (memoriesById.isEmpty()) return emptyList()

        // TODO: 여기에도 userId에 의한 검증이 필요
        val memoryIndices = searcher.search(
            query = query,
            limit = ROUTE_LIMIT,
            scope = MemoryIndexSearchScope(
                allowedMemoryIds = memoriesById.keys.toSet(),
            )
        )

        val allChildrenMemories = memoryIndices.flatMap { (memoryId, _) ->
            getAllChildrenIds(memoriesById, memoryId)
        }

        return allChildrenMemories
    }

    private fun getAllChildrenIds(memoriesById: Map<Int, Memory>, memoryId: Int): List<Int> {
        return memoriesById[memoryId]?.let { memory ->
            val allChildrenIds = memory.childrenIds.flatMap { childId ->
                getAllChildrenIds(memoriesById, childId)
            }
            memory.childrenIds + allChildrenIds
        } ?: emptyList()
    }

    private companion object {
        const val MAX_TRAVERSAL_DEPTH = 12
        const val ROUTE_LIMIT = 6
        const val CHILD_LIMIT = 6
        const val FLAT_FALLBACK_MULTIPLIER = 4
    }
}
