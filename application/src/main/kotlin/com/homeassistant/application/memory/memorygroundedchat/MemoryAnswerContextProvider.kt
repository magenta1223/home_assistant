package com.homeassistant.application.memory.memorygroundedchat

import com.homeassistant.application.memory.read.MemoryIndexSearchScope
import com.homeassistant.application.memory.read.MemoryReader
import com.homeassistant.application.memory.read.MemorySearchMatch
import com.homeassistant.application.memory.read.MemorySearchMatchSource
import com.homeassistant.application.memory.read.MemorySearcher
import com.homeassistant.application.memory.read.SearchMemoriesRequest
import com.homeassistant.application.memory.read.SearchMemoriesResult
import com.homeassistant.application.memory.read.SemanticMemoryIndexSearcher
import com.homeassistant.domain.identity.UserId

/** Builds bounded answer context while leaving direct memory retrieval unchanged. */
class MemoryAnswerContextProvider(
    private val memorySearcher: MemorySearcher,
    private val memories: MemoryReader,
    private val semanticSearcher: SemanticMemoryIndexSearcher,
) {
    fun context(request: SearchMemoriesRequest): SearchMemoriesResult {
        val seedResult = memorySearcher.search(request)
        if (seedResult.matches.isEmpty()) return seedResult
        val finalContextLimit = request.limit + MAX_EXPANDED_MATCHES

        val userId = UserId(request.userId)
        val visibleMemoriesById = memories.getMemories(userId)
            .filter { it.isVisibleTo(userId) }
            .associateBy { it.id }
        val seedIds = seedResult.matches.mapTo(mutableSetOf()) { it.memoryId }
        val seedScoreById = seedResult.matches.associate { it.memoryId to it.score }
        val parentByCandidateId = linkedMapOf<Int, Int>()
        seedResult.matches.forEach { seed ->
            visibleMemoriesById[seed.memoryId]
                ?.childrenIds
                .orEmpty()
                .asSequence()
                .filter { childId -> childId !in seedIds && childId in visibleMemoriesById }
                .take(MAX_CHILD_CANDIDATES_PER_SEED)
                .forEach { childId ->
                    if (parentByCandidateId.size < MAX_TOTAL_CHILD_CANDIDATES) {
                        parentByCandidateId.putIfAbsent(childId, seed.memoryId)
                    }
                }
        }
        if (parentByCandidateId.isEmpty()) return seedResult

        val searchLimit = minOf(parentByCandidateId.size, SearchMemoriesRequest.MAX_LIMIT)
        val rankedChildren = semanticSearcher.search(
            query = seedResult.query,
            limit = searchLimit,
            scope = MemoryIndexSearchScope(parentByCandidateId.keys),
        )
        val expandedCountByParent = mutableMapOf<Int, Int>()
        val expandedMatches = rankedChildren
            .take(searchLimit)
            .distinctBy { it.memoryId }
            .mapNotNull { index ->
                val parentId = parentByCandidateId[index.memoryId] ?: return@mapNotNull null
                val parentScore = seedScoreById.getValue(parentId)
                if (index.score < parentScore * MIN_CHILD_TO_PARENT_SCORE_RATIO) return@mapNotNull null
                val expandedCount = expandedCountByParent[parentId] ?: 0
                if (expandedCount >= MAX_EXPANDED_CHILDREN_PER_SEED) return@mapNotNull null
                val memory = visibleMemoriesById[index.memoryId] ?: return@mapNotNull null
                expandedCountByParent[parentId] = expandedCount + 1
                MemorySearchMatch(
                    memoryId = memory.id,
                    content = memory.content,
                    evidenceRefs = memory.evidenceRefs,
                    score = index.score,
                    createdAt = memory.createdAt,
                    source = MemorySearchMatchSource.CHILD,
                    parentMemoryId = parentId,
                    depth = MAX_EXPANSION_DEPTH,
                )
            }
            .take(minOf(MAX_EXPANDED_MATCHES, finalContextLimit - seedResult.matches.size))

        return SearchMemoriesResult(
            seedResult.query,
            (seedResult.matches + expandedMatches).take(finalContextLimit),
        )
    }

    companion object {
        const val MAX_EXPANSION_DEPTH = 1
        const val MAX_CHILD_CANDIDATES_PER_SEED = 5
        const val MAX_TOTAL_CHILD_CANDIDATES = 10
        const val MAX_EXPANDED_CHILDREN_PER_SEED = 1
        const val MAX_EXPANDED_MATCHES = 2
        const val MIN_CHILD_TO_PARENT_SCORE_RATIO = 0.8
    }
}
