package com.homeassistant.application.usecase.memory.answer

import com.homeassistant.application.port.output.memory.search.MemoryIndex
import com.homeassistant.application.port.output.memory.search.MemoryIndexSearchScope
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.input.memory.search.MemorySearchMatchSource
import com.homeassistant.application.usecase.memory.search.MemorySearcher
import com.homeassistant.application.port.input.memory.search.SearchMemoriesRequest
import com.homeassistant.application.port.output.memory.search.SemanticMemoryIndexSearcher
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryAnswerContextProviderTest {
    private val userId = UserId("member-1")

    @Test
    fun `exact leaf context preserves the full direct ranking`() {
        val memories = listOf(memory(1), memory(2), memory(3))
        val semanticSearcher = ScopedSemanticSearcher(
            directScope = memories.mapTo(mutableSetOf()) { it.id },
            directResults = listOf(MemoryIndex(1, 0.95), MemoryIndex(2, 0.9), MemoryIndex(3, 0.8)),
        )

        val result = provider(memories, semanticSearcher).context(request(limit = 3))

        assertEquals(listOf(1, 2, 3), result.directMatches.map { it.memoryId })
        assertEquals(result.directMatches, result.contextMatches)
        assertTrue(result.contextMatches.all { it.source == MemorySearchMatchSource.DIRECT })
        assertEquals(listOf(3), semanticSearcher.directLimits)
    }

    @Test
    fun `broad parent adds only its highest-ranked direct child`() {
        val parent = memory(1, childrenIds = listOf(2, 3, 4))
        val relevantChild = memory(2)
        val irrelevantSibling = memory(3)
        val otherSibling = memory(4)
        val memories = listOf(parent, relevantChild, irrelevantSibling, otherSibling)
        val semanticSearcher = ScopedSemanticSearcher(
            directScope = memories.mapTo(mutableSetOf()) { it.id },
            directResults = listOf(MemoryIndex(parent.id, 0.96)),
            childResults = listOf(
                MemoryIndex(relevantChild.id, 0.92),
                MemoryIndex(irrelevantSibling.id, 0.2),
                MemoryIndex(otherSibling.id, 0.1),
            ),
        )

        val result = provider(memories, semanticSearcher).context(request(limit = 2))

        assertEquals(listOf(parent.id), result.directMatches.map { it.memoryId })
        assertEquals(listOf(parent.id, relevantChild.id), result.contextMatches.map { it.memoryId })
        assertEquals(MemorySearchMatchSource.DIRECT, result.contextMatches[0].source)
        assertEquals(MemorySearchMatchSource.CHILD, result.contextMatches[1].source)
        assertEquals(parent.id, result.contextMatches[1].parentMemoryId)
        assertEquals(1, result.contextMatches[1].depth)
        assertEquals(0.92, result.contextMatches[1].score)
        assertEquals(2, result.contextMatches.size)
    }

    @Test
    fun `expansion preserves every direct seed within a separate bounded context limit`() {
        val firstParent = memory(1, childrenIds = listOf(2))
        val firstChild = memory(2)
        val secondParent = memory(4, childrenIds = listOf(5))
        val secondChild = memory(5)
        val leaf = memory(7)
        val memories = listOf(firstParent, firstChild, secondParent, secondChild, leaf)
        val semanticSearcher = ScopedSemanticSearcher(
            directScope = memories.mapTo(mutableSetOf()) { it.id },
            directResults = listOf(
                MemoryIndex(firstParent.id, 0.95),
                MemoryIndex(secondParent.id, 0.9),
                MemoryIndex(leaf.id, 0.85),
            ),
            childResults = listOf(MemoryIndex(firstChild.id, 0.9), MemoryIndex(secondChild.id, 0.8)),
        )

        val result = provider(memories, semanticSearcher).context(request(limit = 3))

        assertEquals(
            listOf(firstParent.id, secondParent.id, leaf.id, firstChild.id, secondChild.id),
            result.contextMatches.map { it.memoryId },
        )
        assertEquals(listOf(firstParent.id, secondParent.id, leaf.id), result.directMatches.map { it.memoryId })
        assertEquals(3 + MemoryAnswerContextProvider.MAX_EXPANDED_MATCHES, result.contextMatches.size)
    }

    @Test
    fun `child below its parent-relative relevance gate is excluded`() {
        val parent = memory(1, childrenIds = listOf(2))
        val unrelatedChild = memory(2)
        val memories = listOf(parent, unrelatedChild)
        val semanticSearcher = ScopedSemanticSearcher(
            directScope = memories.mapTo(mutableSetOf()) { it.id },
            directResults = listOf(MemoryIndex(parent.id, 0.9)),
            childResults = listOf(MemoryIndex(unrelatedChild.id, 0.3)),
        )

        val result = provider(memories, semanticSearcher).context(request(limit = 2))

        assertEquals(listOf(parent.id), result.contextMatches.map { it.memoryId })
        assertEquals(setOf(unrelatedChild.id), semanticSearcher.childScopes.single())
    }

    @Test
    fun `zero and negative scores cannot qualify children for expansion`() {
        listOf(
            0.0 to 0.0,
            -0.5 to -0.3,
        ).forEach { (parentScore, childScore) ->
            val parent = memory(1, childrenIds = listOf(2))
            val child = memory(2)
            val memories = listOf(parent, child)
            val semanticSearcher = ScopedSemanticSearcher(
                directScope = memories.mapTo(mutableSetOf()) { it.id },
                directResults = listOf(MemoryIndex(parent.id, parentScore)),
                childResults = listOf(MemoryIndex(child.id, childScore)),
            )

            val result = provider(memories, semanticSearcher).context(request(limit = 1))

            assertEquals(listOf(parent.id), result.contextMatches.map { it.memoryId })
        }
    }

    @Test
    fun `restricted child is excluded before child relevance search`() {
        val parent = memory(1, childrenIds = listOf(2, 3))
        val privateChild = memory(
            id = 2,
            visibility = MemoryVisibility.RESTRICTED,
            allowedUserIds = setOf("member-2"),
        )
        val visibleChild = memory(3)
        val memories = listOf(parent, privateChild, visibleChild)
        val semanticSearcher = ScopedSemanticSearcher(
            directScope = memories.mapTo(mutableSetOf()) { it.id },
            directResults = listOf(MemoryIndex(parent.id, 0.9)),
            childResults = listOf(MemoryIndex(privateChild.id, 0.99), MemoryIndex(visibleChild.id, 0.8)),
        )

        val result = provider(memories, semanticSearcher).context(request(limit = 3))

        assertEquals(listOf(parent.id, visibleChild.id), result.contextMatches.map { it.memoryId })
        assertEquals(setOf(visibleChild.id), semanticSearcher.childScopes.single())
    }

    @Test
    fun `legacy cycle does not repeat seeds or expand beyond direct children`() {
        val parent = memory(1, childrenIds = listOf(2))
        val cyclicChild = memory(2, childrenIds = listOf(1, 3))
        val deepMemory = memory(3)
        val memories = listOf(parent, cyclicChild, deepMemory)
        val semanticSearcher = ScopedSemanticSearcher(
            directScope = memories.mapTo(mutableSetOf()) { it.id },
            directResults = listOf(MemoryIndex(parent.id, 0.9)),
            childResults = listOf(MemoryIndex(cyclicChild.id, 0.8), MemoryIndex(deepMemory.id, 0.99)),
        )

        val result = provider(memories, semanticSearcher).context(request(limit = 5))

        assertEquals(listOf(parent.id, cyclicChild.id), result.contextMatches.map { it.memoryId })
        assertEquals(setOf(cyclicChild.id), semanticSearcher.childScopes.single())
        assertEquals(MemoryAnswerContextProvider.MAX_EXPANSION_DEPTH, result.contextMatches.last().depth)
    }

    private fun provider(
        memories: List<Memory>,
        semanticSearcher: SemanticMemoryIndexSearcher,
    ): MemoryAnswerContextProvider {
        val reader = FixedMemoryReader(memories)
        val directSearcher = MemorySearcher(
            memories = reader,
            searcher = semanticSearcher,
            accessPolicy = UserAccessPolicy { it == userId },
        )
        return MemoryAnswerContextProvider(directSearcher, reader, semanticSearcher)
    }

    private fun request(limit: Int) = SearchMemoriesRequest(userId.value, "question", limit)

    private fun memory(
        id: Int,
        childrenIds: List<Int> = emptyList(),
        visibility: MemoryVisibility = MemoryVisibility.PUBLIC,
        createdByUserId: String = userId.value,
        allowedUserIds: Set<String> = emptySet(),
    ) = Memory(
        id = id,
        childrenIds = childrenIds,
        createdByUserId = createdByUserId,
        content = "memory-$id",
        subject = "subject-$id",
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.OBSERVED,
        visibility = visibility,
        allowedUserIds = allowedUserIds,
        evidenceRefs = listOf(id),
        createdAt = id * 1_000L,
    )

    private class FixedMemoryReader(
        private val memories: List<Memory>,
    ) : MemoryReader {
        override fun getMemories(userId: UserId): List<Memory> = memories
    }

    private class ScopedSemanticSearcher(
        private val directScope: Set<Int>,
        private val directResults: List<MemoryIndex>,
        private val childResults: List<MemoryIndex> = emptyList(),
    ) : SemanticMemoryIndexSearcher {
        val childScopes = mutableListOf<Set<Int>>()
        val directLimits = mutableListOf<Int>()

        override fun search(query: String, limit: Int): List<MemoryIndex> = directResults.take(limit)

        override fun search(
            query: String,
            limit: Int,
            scope: MemoryIndexSearchScope,
        ): List<MemoryIndex> {
            val allowedIds = scope.allowedMemoryIds.orEmpty()
            val results = if (allowedIds == directScope) {
                directLimits += limit
                directResults
            } else {
                childScopes += allowedIds
                childResults
            }
            return results.filter { it.memoryId in allowedIds }.take(limit)
        }
    }
}
