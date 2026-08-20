package com.homeassistant.application.usecase.memory.search

import com.homeassistant.application.port.input.memory.search.MemorySearchUnavailableException
import com.homeassistant.application.port.input.memory.search.SearchMemoriesRequest
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.memory.search.MemoryIndex
import com.homeassistant.application.port.output.memory.search.MemoryIndexSearchScope
import com.homeassistant.application.port.output.memory.search.SemanticMemoryIndexSearcher

import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class MemorySearcherTest {
    private val userId = UserId("member-1")

    @Test
    fun `includes a leaf memory returned by semantic search`() {
        val leaf = memory(1)
        val searcher = RecordingIndexSearcher(listOf(MemoryIndex(leaf.id, 0.91)))

        val result = memorySearcher(listOf(leaf), searcher).search(request())

        assertEquals(listOf(leaf.id), result.matches.map { it.memoryId })
    }

    @Test
    fun `validates and applies the requested limit`() {
        assertFailsWith<IllegalArgumentException> { request(limit = 0) }
        assertFailsWith<IllegalArgumentException> { request(limit = 11) }

        val searcher = RecordingIndexSearcher(
            listOf(
                MemoryIndex(1, 0.9),
                MemoryIndex(2, 0.8),
                MemoryIndex(3, 0.7),
            ),
        )

        val result = memorySearcher(listOf(memory(1), memory(2), memory(3)), searcher)
            .search(request(limit = 2))

        assertEquals(2, searcher.requestedLimit)
        assertEquals(listOf(1, 2), result.matches.map { it.memoryId })
    }

    @Test
    fun `removes duplicate IDs while preserving first-hit order and score`() {
        val searcher = RecordingIndexSearcher(
            listOf(
                MemoryIndex(2, 0.92),
                MemoryIndex(1, 0.87),
                MemoryIndex(2, 0.42),
                MemoryIndex(3, 0.81),
            ),
        )

        val result = memorySearcher(listOf(memory(1), memory(2), memory(3)), searcher)
            .search(request(limit = 4))

        assertEquals(listOf(2, 1, 3), result.matches.map { it.memoryId })
        assertEquals(listOf(0.92, 0.87, 0.81), result.matches.map { it.score })
        assertEquals(listOf(2_000L, 1_000L, 3_000L), result.matches.map { it.createdAt })
    }

    @Test
    fun `translates output failures to the memory search failure contract`() {
        val failure = IllegalStateException("index unavailable")
        val searcher = object : SemanticMemoryIndexSearcher {
            override fun search(query: String, limit: Int): List<MemoryIndex> = throw failure

            override fun search(
                query: String,
                limit: Int,
                scope: MemoryIndexSearchScope,
            ): List<MemoryIndex> = throw failure
        }

        val unavailable = assertFailsWith<MemorySearchUnavailableException> {
            memorySearcher(listOf(memory(1)), searcher).search(request())
        }

        assertSame(failure, unavailable.cause)
    }

    private fun memorySearcher(
        memories: List<Memory>,
        searcher: SemanticMemoryIndexSearcher,
    ) = MemorySearcher(
        memories = FixedMemoryReader(memories),
        searcher = searcher,
        accessPolicy = UserAccessPolicy { it == userId },
    )

    private fun request(limit: Int = 5) = SearchMemoriesRequest(
        userId = userId.value,
        query = " query ",
        limit = limit,
    )

    private fun memory(id: Int) = Memory(
        id = id,
        createdByUserId = userId.value,
        content = "memory-$id",
        subject = "subject-$id",
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.OBSERVED,
        visibility = MemoryVisibility.PUBLIC,
        evidenceRefs = listOf(id),
        createdAt = id * 1_000L,
    )

    private class FixedMemoryReader(
        private val memories: List<Memory>,
    ) : MemoryReader {
        override fun getMemories(userId: UserId): List<Memory> = memories
    }

    private class RecordingIndexSearcher(
        private val results: List<MemoryIndex>,
    ) : SemanticMemoryIndexSearcher {
        var requestedLimit: Int? = null

        override fun search(query: String, limit: Int): List<MemoryIndex> = results

        override fun search(
            query: String,
            limit: Int,
            scope: MemoryIndexSearchScope,
        ): List<MemoryIndex> {
            requestedLimit = limit
            return results
        }
    }
}
