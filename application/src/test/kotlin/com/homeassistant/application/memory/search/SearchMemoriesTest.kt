package com.homeassistant.application.memory.search

import com.homeassistant.application.memory.MemoryContext
import com.homeassistant.application.memory.MemoryTopicContext
import com.homeassistant.application.memory.io.SemanticMemoryIndexSearcher
import com.homeassistant.application.memory.io.MemoryIndex
import com.homeassistant.application.memory.io.MemoryReader
import com.homeassistant.application.memory.io.SearchMemories
import com.homeassistant.application.memory.io.SearchMemoriesRequest
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.*
import com.homeassistant.domain.source.SourceDescriptor
import kotlin.test.*

class SearchMemoriesTest {
    @Test
    fun `returns canonical memories in semantic hit order`() {
        val reader = FakeMemoryReader(listOf(context(1, 7), context(2, 8)))
        val searcher = FakeSemanticMemoryIndexSearcher(
            listOf(MemoryIndex(2, 0.9), MemoryIndex(1, 0.8)),
        )
        val useCase = SearchMemories(reader, searcher, AUTHORIZED)

        val result = useCase.search(SearchMemoriesRequest("dad", "리모컨", 5))

        assertEquals(listOf(2, 1), result.matches.map { it.memoryId })
    }

    @Test
    fun `returns standalone memory without topic context`() {
        val standalone = context(3, null)
        val useCase = SearchMemories(
            FakeMemoryReader(listOf(standalone)),
            FakeSemanticMemoryIndexSearcher(listOf(MemoryIndex(3, 1.0))),
            AUTHORIZED,
        )

        val match = useCase.search(SearchMemoriesRequest("dad", "독립", 5)).matches.single()

        assertNull(match.topicId)
        assertNull(match.topicTitle)
        assertEquals("기억 3", match.content)
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
        val privateMemory = context(4, null).copy(
            memory = context(4, null).memory.copy(
                createdByUserId = "mom",
                visibility = MemoryVisibility.PRIVATE,
            ),
        )
        val useCase = SearchMemories(
            FakeMemoryReader(listOf(privateMemory)),
            FakeSemanticMemoryIndexSearcher(listOf(MemoryIndex(4, 1.0))),
            AUTHORIZED,
        )

        assertEquals(emptyList(), useCase.search(SearchMemoriesRequest("dad", "비밀", 5)).matches)
    }

    @Test
    fun `rejects unauthorized user before search`() {
        val searcher = FakeSemanticMemoryIndexSearcher(emptyList())
        val useCase = SearchMemories(FakeMemoryReader(emptyList()), searcher, AUTHORIZED)

        assertFailsWith<HouseholdAccessDeniedException> {
            useCase.search(SearchMemoriesRequest("stranger", "질문", 5))
        }
    }
}

private class FakeMemoryReader(private val contexts: List<MemoryContext>) : MemoryReader {
    override fun findByIds(memoryIds: Collection<Int>): List<MemoryContext> =
        contexts.filter { it.memory.id in memoryIds }

    override fun findVisibleByIds(userId: UserId, memoryIds: Collection<Int>): List<MemoryContext> =
        findByIds(memoryIds).filter { it.memory.isVisibleTo(userId) }
}

private class FakeSemanticMemoryIndexSearcher(
    private val hits: List<MemoryIndex>,
) : SemanticMemoryIndexSearcher {
    override fun search(query: String, limit: Int): List<MemoryIndex> = hits.take(limit)
}

private fun context(memoryId: Int, topicId: Int?) = MemoryContext(
    memory = Memory(
        memoryId, topicId, "dad", "기억 $memoryId", "대상", MemoryType.REFERENCE,
        MemoryCertainty.SAID, MemoryVisibility.FAMILY, listOf(memoryId * 10),
    ),
    topic = topicId?.let {
        MemoryTopicContext(it, "주제 $it", "요약 $it", SourceDescriptor("kakao", "family.txt"))
    },
)

private val AUTHORIZED = HouseholdAccessPolicy { it == UserId("dad") }
