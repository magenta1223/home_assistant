package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.topic.TopicMatch
import com.homeassistant.application.port.input.topic.TopicMatchMemory
import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.group.GroupReader
import com.homeassistant.application.port.output.group.StoredGroup
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.topic.MemoryEmbedding
import com.homeassistant.application.port.output.topic.MemoryEmbeddingKey
import com.homeassistant.application.port.output.topic.MemoryEmbeddingStore
import com.homeassistant.application.port.output.topic.QueryEmbeddingGenerator
import com.homeassistant.application.port.output.topic.TopicReader
import com.homeassistant.domain.group.Group
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
    private val space = EmbeddingSpace("test", "passage:v1")
    private val east = TopicVector.normalized(listOf(1.0, 0.0))
    private val north = TopicVector.normalized(listOf(0.0, 1.0))
    private val northEast = TopicVector.normalized(listOf(1.0, 1.0))
    private val onlyB = MemoryAccess.restricted(listOf(UserId("b")))
    private val ab = MemoryAccess.restricted(listOf(a, UserId("b")))

    @Test fun `backbone topics come through readable slices only, memories most relevant first`() {
        val fixture = Fixture(
            topic(1, east, MemoryAccess.PUBLIC to listOf(10), ab to listOf(11), onlyB to listOf(12)),
            topic(2, east, onlyB to listOf(20)),
            topic(3, northEast, MemoryAccess.PUBLIC to listOf(30)),
            topic(4, north, MemoryAccess.PUBLIC to listOf(40)),
        )
        fixture.memoryVectors[10] = north

        val matches = fixture.searcher(TopicSearchSettings(topicLimit = 2)).search(a, "  question  ")

        assertEquals(listOf("question"), fixture.queries)
        assertEquals(listOf(1, 3), matches.map { it.topicId })
        assertEquals(1.0, matches.first().score, 1e-12)
        assertEquals(listOf(11, 10), matches.first().memories.map { it.memoryId })
        assertEquals(TopicMatchMemory(30, "memory 30", 30L), matches.last().memories.single())
    }

    @Test fun `a topic outside the nearest topics is found through its backbone group`() {
        val fixture = Fixture(
            topic(1, east, MemoryAccess.PUBLIC to listOf(10)),
            topic(2, north, MemoryAccess.PUBLIC to listOf(20)),
        )
        val narrow = TopicSearchSettings(topicPool = 1)

        assertEquals(listOf(1), fixture.searcher(narrow).search(a, "q").map { it.topicId })
        fixture.groups += StoredGroup(Group(1, setOf(2)), 1, east)
        assertEquals(listOf(1, 2), fixture.searcher(narrow).search(a, "q").map { it.topicId })
    }

    @Test fun `a topic of a neighbor group outside the nearest groups is found`() {
        val fixture = Fixture(
            topic(1, east, MemoryAccess.PUBLIC to listOf(10)),
            topic(2, north, MemoryAccess.PUBLIC to listOf(20)),
            topic(3, north, MemoryAccess.PUBLIC to listOf(30)),
        )
        fixture.groups += listOf(
            StoredGroup(Group(1, setOf(1)), 1, east),
            StoredGroup(Group(2, setOf(2)), 1, northEast),
            StoredGroup(Group(3, setOf(3)), 1, north),
        )

        val matches = fixture.searcher(TopicSearchSettings(groupPool = 1, groupLimit = 2, topicPool = 1)).search(a, "q")

        assertEquals(listOf(1, 2), matches.map { it.topicId })
    }

    @Test fun `neighbor groups fill the candidates by their similarity to the group they neighbor, not to the question`() {
        val fixture = Fixture(
            topic(1, east, MemoryAccess.PUBLIC to listOf(10)),
            topic(2, northEast, MemoryAccess.PUBLIC to listOf(20)),
            topic(3, north, MemoryAccess.PUBLIC to listOf(30)),
        )
        // At -20°, -60° and 30° from the question: group 3 is nearer to the question than group 2 but less similar to
        // group 1, so the cap of 2 keeps group 2.
        fixture.groups += listOf(
            StoredGroup(Group(1, setOf(1)), 1, degrees(-20.0)),
            StoredGroup(Group(2, setOf(2)), 1, degrees(-60.0)),
            StoredGroup(Group(3, setOf(3)), 1, degrees(30.0)),
        )
        val narrow = TopicSearchSettings(groupPool = 1, groupCandidates = 2, groupLimit = 3, topicPool = 1)

        assertEquals(setOf(1, 2), fixture.searcher(narrow).search(a, "q").map { it.topicId }.toSet())
    }

    @Test fun `a memory is listed under its first topic only and a topic left empty is dropped`() {
        val fixture = Fixture(
            topic(1, east, MemoryAccess.PUBLIC to listOf(10, 11)),
            topic(2, northEast, MemoryAccess.PUBLIC to listOf(11, 20)),
            topic(3, north, MemoryAccess.PUBLIC to listOf(10)),
        )

        val matches = fixture.searcher().search(a, "q")

        assertEquals(listOf(1, 2), matches.map { it.topicId })
        assertEquals(listOf(listOf(10, 11), listOf(20)), matches.map { match -> match.memories.map { it.memoryId } })
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
        assertFailsWith<UserAccessDeniedException> {
            Fixture(topic(1, east, MemoryAccess.PUBLIC to listOf(10))).searcher(authorized = false).search(a, "q")
        }
    }

    private fun degrees(angle: Double) =
        TopicVector.normalized(listOf(kotlin.math.cos(Math.toRadians(angle)), kotlin.math.sin(Math.toRadians(angle))))

    private fun topic(id: Int, vector: TopicVector, vararg slices: Pair<MemoryAccess, List<Int>>): Topic {
        val count = slices.sumOf { it.second.size }
        val centroid = (2..count).fold(TopicCentroid.of(vector)) { sum, _ -> sum + vector }
        return Topic(id, centroid, slices.toMap())
    }

    private inner class Fixture(vararg topics: Topic) {
        private val byId = topics.associateBy { it.id }
        val queries = mutableListOf<String>()
        val groups = mutableListOf<StoredGroup>()
        val memoryVectors = mutableMapOf<Int, TopicVector>()
        var visible: Set<Int>? = null

        fun searcher(settings: TopicSearchSettings = TopicSearchSettings(), authorized: Boolean = true) = TopicSearcher(
            object : TopicReader {
                override fun centroids() = byId.mapValues { it.value.centroid }
                override fun read(topicIds: Collection<Int>) = topicIds.mapNotNull { byId[it] }
            },
            GroupReader { groups.toList() },
            QueryEmbeddingGenerator { queries.add(it); east },
            object : MemoryReader {
                override fun getMemories(userId: UserId) = byId.values
                    .flatMap { topic -> topic.slices.filterKeys { it.isVisibleTo(userId) }.values.flatten() }
                    .distinct()
                    .filter { visible == null || it in visible!! }
                    .map { Memory(it, "creator", "memory $it", MemoryAccess.PUBLIC.visibility, emptySet(), listOf(1), it.toLong()) }
            },
            object : MemoryEmbeddingStore {
                override fun find(key: MemoryEmbeddingKey) =
                    MemoryEmbedding(key, memoryVectors[key.memoryId] ?: east).takeIf { key == MemoryEmbeddingKey.of(key.memoryId, "memory ${key.memoryId}", space) }
                override fun save(embedding: MemoryEmbedding) = embedding
            },
            space,
            UserAccessPolicy { authorized },
            settings,
        )
    }
}
