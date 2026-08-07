package com.homeassistant.application.memory.search

import com.homeassistant.application.memory.io.MemoryIndex
import com.homeassistant.application.memory.io.MemoryReader
import com.homeassistant.application.memory.io.SearchMemories
import com.homeassistant.application.memory.io.SearchMemoriesRequest
import com.homeassistant.application.memory.io.SemanticMemoryIndexSearcher
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.*
import kotlin.test.*

class SearchMemoriesTest {
    @Test
    fun `returns canonical memories in semantic hit order`() {
        val reader = FakeMemoryReader(listOf(memory(1), memory(2)))
        val useCase = SearchMemories(
            reader,
            FakeSemanticMemoryIndexSearcher(listOf(MemoryIndex(2, 0.9), MemoryIndex(1, 0.8))),
            AUTHORIZED,
        )

        assertEquals(listOf(2, 1), useCase.search(SearchMemoriesRequest("dad", "리모컨", 5)).matches.map { it.memoryId })
    }

    @Test
    fun `returns flat memory content and evidence`() {
        val useCase = SearchMemories(
            FakeMemoryReader(listOf(memory(3))),
            FakeSemanticMemoryIndexSearcher(listOf(MemoryIndex(3, 1.0))),
            AUTHORIZED,
        )

        val match = useCase.search(SearchMemoriesRequest("dad", "독립", 5)).matches.single()

        assertEquals("기억 3", match.content)
        assertEquals(listOf(30), match.evidenceRefs)
    }

    @Test
    fun `drops stale vector hit`() {
        val useCase = SearchMemories(
            FakeMemoryReader(emptyList()),
            FakeSemanticMemoryIndexSearcher(listOf(MemoryIndex(99, 1.0))),
            AUTHORIZED,
        )
        assertEquals(emptyList(), useCase.search(SearchMemoriesRequest("dad", "없음", 5)).matches)
    }

    @Test
    fun `filters private memories for a different user`() {
        val privateMemory = memory(4).copy(createdByUserId = "mom", visibility = MemoryVisibility.PRIVATE)
        val useCase = SearchMemories(
            FakeMemoryReader(listOf(privateMemory)),
            FakeSemanticMemoryIndexSearcher(listOf(MemoryIndex(4, 1.0))),
            AUTHORIZED,
        )
        assertEquals(emptyList(), useCase.search(SearchMemoriesRequest("dad", "비밀", 5)).matches)
    }

    @Test
    fun `rejects unauthorized user before search`() {
        val useCase = SearchMemories(
            FakeMemoryReader(emptyList()),
            FakeSemanticMemoryIndexSearcher(emptyList()),
            AUTHORIZED,
        )
        assertFailsWith<HouseholdAccessDeniedException> {
            useCase.search(SearchMemoriesRequest("stranger", "질문", 5))
        }
    }
}

private class FakeMemoryReader(private val memories: List<Memory>) : MemoryReader {
    override fun findByIds(memoryIds: Collection<Int>): List<Memory> = memories.filter { it.id in memoryIds }
    override fun findVisibleByIds(userId: UserId, memoryIds: Collection<Int>): List<Memory> =
        findByIds(memoryIds).filter { it.isVisibleTo(userId) }
}

private class FakeSemanticMemoryIndexSearcher(
    private val hits: List<MemoryIndex>,
) : SemanticMemoryIndexSearcher {
    override fun search(query: String, limit: Int): List<MemoryIndex> = hits.take(limit)
}

private fun memory(id: Int) = Memory(
    id = id,
    parentId = null,
    createdByUserId = "dad",
    content = "기억 $id",
    subject = "대상",
    memoryType = MemoryType.REFERENCE,
    certainty = MemoryCertainty.SAID,
    visibility = MemoryVisibility.FAMILY,
    evidenceRefs = listOf(id * 10),
)

private val AUTHORIZED = HouseholdAccessPolicy { it == UserId("dad") }
