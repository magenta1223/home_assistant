package com.homeassistant.application.memory.search

import com.homeassistant.application.memory.CanonicalMemoryContext
import com.homeassistant.application.memory.MemoryTopicContext
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
        val searcher = FakeMemorySearcher(listOf(MemorySearchHit(2, 0.9), MemorySearchHit(1, 0.8)))
        val useCase = SearchMemories(reader, searcher, AUTHORIZED)

        val result = useCase.search(SearchMemoriesRequest("dad", "리모컨", 5))

        assertEquals(listOf(2, 1), result.matches.map { it.memoryId })
    }

    @Test
    fun `returns standalone memory without topic context`() {
        val standalone = context(3, null)
        val useCase = SearchMemories(FakeMemoryReader(listOf(standalone)), FakeMemorySearcher(listOf(MemorySearchHit(3, 1.0))), AUTHORIZED)

        val match = useCase.search(SearchMemoriesRequest("dad", "독립", 5)).matches.single()

        assertNull(match.topicId)
        assertNull(match.topicTitle)
        assertEquals("기억 3", match.content)
    }

    @Test
    fun `drops stale vector hit`() {
        val useCase = SearchMemories(FakeMemoryReader(emptyList()), FakeMemorySearcher(listOf(MemorySearchHit(99, 1.0))), AUTHORIZED)

        assertEquals(emptyList(), useCase.search(SearchMemoriesRequest("dad", "없음", 5)).matches)
    }

    @Test
    fun `rejects unauthorized user before search`() {
        val searcher = FakeMemorySearcher(emptyList())
        val useCase = SearchMemories(FakeMemoryReader(emptyList()), searcher, AUTHORIZED)

        assertFailsWith<HouseholdAccessDeniedException> {
            useCase.search(SearchMemoriesRequest("stranger", "질문", 5))
        }
    }
}

private class FakeMemoryReader(private val contexts: List<CanonicalMemoryContext>) : CanonicalMemoryReader {
    override fun findVisibleByIds(userId: UserId, memoryIds: Collection<Int>): List<CanonicalMemoryContext> =
        contexts.filter { it.memory.id in memoryIds }
}

private class FakeMemorySearcher(private val hits: List<MemorySearchHit>) : MemorySearcher {
    override fun search(query: String, limit: Int): List<MemorySearchHit> = hits.take(limit)
}

private fun context(memoryId: Int, topicId: Int?) = CanonicalMemoryContext(
    memory = Memory(
        memoryId, topicId, "dad", "기억 $memoryId", "대상", MemoryType.REFERENCE,
        MemoryCertainty.SAID, MemoryVisibility.FAMILY, listOf(memoryId * 10),
    ),
    topic = topicId?.let {
        MemoryTopicContext(it, "주제 $it", "요약 $it", SourceDescriptor("kakao", "family.txt"))
    },
)

private val AUTHORIZED = HouseholdAccessPolicy { it == UserId("dad") }
