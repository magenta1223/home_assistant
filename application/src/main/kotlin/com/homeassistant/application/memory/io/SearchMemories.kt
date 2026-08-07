package com.homeassistant.application.memory.io

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryVisibility
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
        val limit = request.limit.coerceIn(1, 10)
        val hierarchicalHits = searchHierarchically(query, limit)
        val hits = if (hierarchicalHits.isNotEmpty()) {
            hierarchicalHits
        } else {
            searcher.search(
                query,
                limit * FLAT_FALLBACK_MULTIPLIER,
                MemoryIndexSearchScope(),
            )
        }
        val memoriesById = memories.findVisibleLeafByIds(userId, hits.map { it.memoryId }).associateBy { it.id }
        val matches = hits.distinctBy { it.memoryId }.take(limit).mapNotNull { hit ->
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

    private fun searchHierarchically(query: String, limit: Int): List<MemoryIndex> {
        val rootIds = memories.findRootMemories()
            .filter { it.visibility == MemoryVisibility.STRUCTURAL }
            .mapTo(mutableSetOf()) { it.id }
        if (rootIds.isEmpty()) return emptyList()
        val routeHits = searcher.search(
            query = query,
            limit = ROUTE_LIMIT,
            scope = MemoryIndexSearchScope(
                allowedMemoryIds = rootIds,
            ),
        )
        if (routeHits.isEmpty()) return emptyList()

        val structuralIds = memories.findByIds(routeHits.map { it.memoryId })
            .filter { it.visibility == MemoryVisibility.STRUCTURAL }
            .mapTo(mutableSetOf()) { it.id }
        if (structuralIds.isEmpty()) return emptyList()

        return routeHits
            .filter { it.memoryId in structuralIds }
            .flatMap { descend(query, it.memoryId, it.score, 0) }
            .sortedByDescending { it.score }
            .distinctBy { it.memoryId }
            .take(limit)
    }

    private fun descend(
        query: String,
        containerId: Int,
        parentScore: Double,
        depth: Int,
    ): List<MemoryIndex> {
        if (depth >= MAX_TRAVERSAL_DEPTH) return emptyList()
        val parent = memories.findByIds(listOf(containerId)).singleOrNull() ?: return emptyList()
        if (parent.childrenIds.isEmpty()) return emptyList()
        val childHits = searcher.search(
            query = query,
            limit = CHILD_LIMIT,
            scope = MemoryIndexSearchScope(allowedMemoryIds = parent.childrenIds.toSet()),
        )
        if (childHits.isEmpty()) return emptyList()
        val children = memories.findByIds(childHits.map { it.memoryId }).associateBy { it.id }
        return childHits.flatMap { hit ->
            val child = children[hit.memoryId] ?: return@flatMap emptyList()
            val combinedScore = (parentScore + hit.score) / 2.0
            if (child.visibility == MemoryVisibility.STRUCTURAL) {
                descend(query, child.id, combinedScore, depth + 1)
            } else {
                listOf(MemoryIndex(child.id, combinedScore))
            }
        }
    }

    private companion object {
        const val MAX_TRAVERSAL_DEPTH = 12
        const val ROUTE_LIMIT = 6
        const val CHILD_LIMIT = 6
        const val FLAT_FALLBACK_MULTIPLIER = 4
    }
}
