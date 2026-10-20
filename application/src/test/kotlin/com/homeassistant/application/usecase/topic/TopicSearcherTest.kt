package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.topic.TopicMatch
import com.homeassistant.application.port.input.topic.TopicMatchMemory
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.topic.QueryEmbeddingGenerator
import com.homeassistant.application.port.output.topic.TopicReader
import com.homeassistant.domain.group.TopicVector
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.topic.Topic
import com.homeassistant.domain.topic.TopicCentroid
import kotlin.test.*

class TopicSearcherTest {
    private val a = UserId("a")
    private val east = TopicVector.normalized(listOf(1.0, 0.0))
    private val north = TopicVector.normalized(listOf(0.0, 1.0))
    private val northEast = TopicVector.normalized(listOf(1.0, 1.0))
    private val onlyB = MemoryAccess.restricted(listOf(UserId("b")))
    private val ab = MemoryAccess.restricted(listOf(a, UserId("b")))

    @Test fun `nearest topics come first through readable slices only, skipping hidden topics`() {
        val fixture = Fixture(
            topic(1, east, MemoryAccess.PUBLIC to listOf(10), ab to listOf(11), onlyB to listOf(12)),
            topic(2, east, onlyB to listOf(20)),
            topic(3, northEast, MemoryAccess.PUBLIC to listOf(30)),
            topic(4, north, MemoryAccess.PUBLIC to listOf(40)),
        )
        val matches = fixture.searcher(limit = 2).search(a, "  question  ")

        assertEquals(listOf("question"), fixture.queries)
        assertEquals(listOf(1, 3), matches.map { it.topicId })
        assertEquals(1.0, matches.first().score, 1e-12)
        assertEquals(listOf(11, 10), matches.first().memories.map { it.memoryId })
        assertEquals(TopicMatchMemory(30, "memory 30", 30L), matches.last().memories.single())
    }

    @Test fun `a slice member the reader no longer shows is dropped`() {
        val fixture = Fixture(topic(1, east, ab to listOf(11, 12)))
        fixture.visible = setOf(11)
        assertEquals(listOf(TopicMatch(1, 1.0, listOf(TopicMatchMemory(11, "memory 11", 11L)))), fixture.searcher().search(a, "q"))
    }

    @Test fun `no topic needs no embedding and an unauthorized user is rejected`() {
        val empty = Fixture()
        assertTrue(empty.searcher().search(a, "q").isEmpty())
        assertTrue(empty.queries.isEmpty())
        assertFailsWith<UserAccessDeniedException> { Fixture(topic(1, east, MemoryAccess.PUBLIC to listOf(10))).searcher(authorized = false).search(a, "q") }
    }

    private fun topic(id: Int, vector: TopicVector, vararg slices: Pair<MemoryAccess, List<Int>>): Topic {
        val count = slices.sumOf { it.second.size }
        val centroid = (2..count).fold(TopicCentroid.of(vector)) { sum, _ -> sum + vector }
        return Topic(id, centroid, slices.toMap())
    }

    private inner class Fixture(vararg topics: Topic) {
        private val byId = topics.associateBy { it.id }
        val queries = mutableListOf<String>()
        var visible: Set<Int>? = null

        fun searcher(limit: Int = 3, authorized: Boolean = true) = TopicSearcher(
            object : TopicReader {
                override fun centroids() = byId.mapValues { it.value.centroid }
                override fun read(topicIds: Collection<Int>) = topicIds.mapNotNull { byId[it] }
            },
            QueryEmbeddingGenerator { queries.add(it); east },
            object : MemoryReader {
                override fun getMemories(userId: UserId) = byId.values
                    .flatMap { topic -> topic.slices.filterKeys { it.isVisibleTo(userId) }.values.flatten() }
                    .filter { visible == null || it in visible!! }
                    .map { Memory(it, "creator", "memory $it", MemoryAccess.PUBLIC.visibility, emptySet(), listOf(1), it.toLong()) }
            },
            UserAccessPolicy { authorized },
            limit,
        )
    }
}
