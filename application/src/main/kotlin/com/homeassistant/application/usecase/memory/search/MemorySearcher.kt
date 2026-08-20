package com.homeassistant.application.usecase.memory.search

import com.homeassistant.application.port.input.memory.search.MemorySearch
import com.homeassistant.application.port.input.memory.search.MemorySearchMatch
import com.homeassistant.application.port.input.memory.search.MemorySearchUnavailableException
import com.homeassistant.application.port.input.memory.search.SearchMemoriesRequest
import com.homeassistant.application.port.input.memory.search.SearchMemoriesResult
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.memory.search.MemoryIndex
import com.homeassistant.application.port.output.memory.search.MemoryIndexSearchScope
import com.homeassistant.application.port.output.memory.search.SemanticMemoryIndexSearcher
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId


class MemorySearcher(
    private val memories: MemoryReader,
    private val searcher: SemanticMemoryIndexSearcher,
    private val accessPolicy: UserAccessPolicy,
) : MemorySearch {
    override fun search(request: SearchMemoriesRequest): SearchMemoriesResult {
        val userId = UserId(request.userId)
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
        val query = request.query.trim()

        return try {
            searchVisibleMemories(userId, query, request.limit)
        } catch (error: Exception) {
            throw MemorySearchUnavailableException(error)
        }
    }

    private fun searchVisibleMemories(userId: UserId, query: String, limit: Int): SearchMemoriesResult {
        val memoriesById = memories.getMemories(userId).associateBy { it.id }
        val retrievedMemoryIndices = semanticSearch(query, limit, memoriesById.keys)
        val matches = retrievedMemoryIndices
            .distinctBy { it.memoryId }
            .mapNotNull { index ->
                memoriesById[index.memoryId]?.let { memory ->
                    MemorySearchMatch(
                        memoryId = memory.id,
                        content = memory.content,
                        evidenceRefs = memory.evidenceRefs,
                        score = index.score,
                        createdAt = memory.createdAt,
                    )
                }
            }
            .take(limit)
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
