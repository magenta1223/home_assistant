package com.homeassistant.application.usecase.memory.answer

import com.homeassistant.application.port.input.memory.search.MemorySearchMatchSource
import com.homeassistant.application.port.input.memory.search.SearchMemoriesRequest
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.memory.search.MemoryIndex
import com.homeassistant.application.port.output.memory.search.MemoryIndexSearchScope
import com.homeassistant.application.port.output.memory.search.SemanticMemoryIndexSearcher
import com.homeassistant.application.usecase.memory.search.MemorySearcher
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryAnswerContextProviderTest {
    @Test
    fun `context preserves direct ranking and evidence without additional retrieval`() {
        val visible = listOf(memory(1), memory(2), memory(3))
        val scopes = mutableListOf<MemoryIndexSearchScope>()
        val reader = object : MemoryReader {
            override fun getMemories(userId: UserId) = visible
        }
        val index = object : SemanticMemoryIndexSearcher {
            override fun search(query: String, limit: Int): List<MemoryIndex> =
                error("An authorized scope is required")

            override fun search(query: String, limit: Int, scope: MemoryIndexSearchScope): List<MemoryIndex> {
                scopes += scope
                assertEquals(2, limit)
                // Even a stale index hit outside the canonical ACL must not enter context.
                return listOf(MemoryIndex(99, 1.0), MemoryIndex(2, 0.9), MemoryIndex(1, 0.8))
            }
        }
        val provider = MemoryAnswerContextProvider(MemorySearcher(reader, index, UserAccessPolicy { true }))

        val result = provider.context(SearchMemoriesRequest("member-1", " question ", 2))

        assertEquals("question", result.query)
        assertEquals(listOf(2, 1), result.directMatches.map { it.memoryId })
        assertEquals(result.directMatches, result.contextMatches)
        assertEquals(listOf(2), result.contextMatches.first().evidenceRefs)
        assertTrue(result.contextMatches.all { it.source == MemorySearchMatchSource.DIRECT })
        assertEquals(setOf(1, 2, 3), scopes.single().allowedMemoryIds)
    }

    private fun memory(id: Int) = Memory(
        id = id,
        createdByUserId = "member-1",
        content = "memory-$id",
        visibility = MemoryVisibility.PUBLIC,
        evidenceRefs = listOf(id),
        createdAt = id * 1_000L,
    )
}
