package com.homeassistant.application.memory.search

import com.homeassistant.application.memory.CanonicalMemoryContext
import com.homeassistant.application.memory.MemoryTopicContext
import com.homeassistant.application.memory.read.CanonicalMemoryReader
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.*
import com.homeassistant.domain.source.SourceDescriptor
import kotlin.test.*

class SearchMemoriesTest {
    @Test
    fun `returns canonical memories in semantic hit order`() {
        val reader = FakeCanonicalMemoryReader(listOf(context(1, 7), context(2, 8)))
        val searcher = FakeSemanticMemoryIndexSearcher(
            listOf(SemanticMemorySearchHit(2, 0.9), SemanticMemorySearchHit(1, 0.8)),
        )
        val useCase = SearchMemories(reader, searcher, AUTHORIZED)

        val result = useCase.search(SearchMemoriesRequest("dad", "리모컨", 5))

        assertEquals(listOf(2, 1), result.matches.map { it.memoryId })
    }

    @Test
    fun `returns standalone memory without topic context`() {
        val standalone = context(3, null)
        val useCase = SearchMemories(
            FakeCanonicalMemoryReader(listOf(standalone)),
            FakeSemanticMemoryIndexSearcher(listOf(SemanticMemorySearchHit(3, 1.0))),
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
            FakeCanonicalMemoryReader(emptyList()),
            FakeSemanticMemoryIndexSearcher(listOf(SemanticMemorySearchHit(99, 1.0))),
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
            FakeCanonicalMemoryReader(listOf(privateMemory)),
            FakeSemanticMemoryIndexSearcher(listOf(SemanticMemorySearchHit(4, 1.0))),
            AUTHORIZED,
        )

        assertEquals(emptyList(), useCase.search(SearchMemoriesRequest("dad", "비밀", 5)).matches)
    }

    @Test
    fun `rejects unauthorized user before search`() {
        val searcher = FakeSemanticMemoryIndexSearcher(emptyList())
        val useCase = SearchMemories(FakeCanonicalMemoryReader(emptyList()), searcher, AUTHORIZED)

        assertFailsWith<HouseholdAccessDeniedException> {
            useCase.search(SearchMemoriesRequest("stranger", "질문", 5))
        }
    }
}

private class FakeCanonicalMemoryReader(private val contexts: List<CanonicalMemoryContext>) : CanonicalMemoryReader {
    override fun findByIds(memoryIds: Collection<Int>): List<CanonicalMemoryContext> =
        contexts.filter { it.memory.id in memoryIds }

    override fun findVisibleByIds(userId: UserId, memoryIds: Collection<Int>): List<CanonicalMemoryContext> =
        findByIds(memoryIds).filter { it.memory.isVisibleTo(userId) }
}

private class FakeSemanticMemoryIndexSearcher(
    private val hits: List<SemanticMemorySearchHit>,
) : SemanticMemoryIndexSearcher {
    override fun search(query: String, limit: Int): List<SemanticMemorySearchHit> = hits.take(limit)
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
