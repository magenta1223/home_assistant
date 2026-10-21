package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.OrganizePendingTopics
import com.homeassistant.application.port.input.topic.*
import com.homeassistant.application.port.output.group.*
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.application.usecase.group.PrepareTopicEmbedding
import com.homeassistant.application.usecase.group.RefreshGroupedTopicEmbeddings
import com.homeassistant.domain.group.*
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.topic.Topic
import com.homeassistant.domain.topic.TopicCentroid
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class OrganizePendingMemoriesTest {
    private val space = EmbeddingSpace("test", "v1")
    private val east = TopicVector.normalized(listOf(1.0, 0.0))
    private val north = TopicVector.normalized(listOf(0.0, 1.0))
    private val nearEast = TopicVector.normalized(listOf(1.0, 0.2))

    @Test fun `without topics a memory starts one from its own vector`() {
        val fixture = Fixture()
        val result = fixture.service.execute(1)
        assertEquals(MemoryAssignmentOutcome.Assigned(listOf(100), created = true), result.memories.single().outcome)
        assertEquals(mapOf(100 to TopicCentroid.of(east)), fixture.centroids)
        assertEquals(mapOf(100 to listOf(1)), fixture.members)
    }

    @Test fun `a memory joins the nearest topic and moves its centroid`() {
        val fixture = Fixture()
        fixture.add(7, TopicCentroid.of(north), 50)
        fixture.add(9, TopicCentroid.of(east), 51)
        fixture.vectors = mapOf("1" to nearEast)
        val outcome = fixture.service.execute(1).memories.single().outcome
        assertEquals(MemoryAssignmentOutcome.Assigned(listOf(9), created = false), outcome)
        assertEquals(TopicCentroid.of(east) + nearEast, fixture.centroids.getValue(9))
        assertEquals(listOf(51, 1), fixture.members.getValue(9))
        assertEquals(TopicCentroid.of(north), fixture.centroids.getValue(7))
    }

    @Test fun `a memory joins every near topic in one write`() {
        val fixture = Fixture()
        fixture.add(7, TopicCentroid.of(north), 50)
        fixture.add(8, TopicCentroid.of(nearEast), 51)
        fixture.add(9, TopicCentroid.of(east), 52)
        val outcome = fixture.service.execute(1).memories.single().outcome
        assertEquals(MemoryAssignmentOutcome.Assigned(listOf(9, 8), created = false), outcome)
        assertEquals(TopicCentroid.of(nearEast) + east, fixture.centroids.getValue(8))
        assertEquals(TopicCentroid.of(east) + east, fixture.centroids.getValue(9))
        assertEquals(listOf(50), fixture.members.getValue(7))
    }

    @Test fun `a memory below the threshold starts a new topic`() {
        val fixture = Fixture()
        fixture.add(7, TopicCentroid.of(north), 50)
        val outcome = fixture.service.execute(1).memories.single().outcome
        assertEquals(MemoryAssignmentOutcome.Assigned(listOf(100), created = true), outcome)
        assertEquals(setOf(7, 100), fixture.centroids.keys)
    }

    @Test fun `a join that lost to another write fails that memory only`() {
        val fixture = Fixture()
        fixture.add(7, TopicCentroid.of(east), 50)
        fixture.losesJoin = setOf(1)
        val result = fixture.service.execute(2)
        assertIs<MemoryAssignmentOutcome.Failed>(result.memories.first().outcome)
        assertEquals(MemoryAssignmentOutcome.Assigned(listOf(7), created = false), result.memories.last().outcome)
        assertEquals(listOf(50, 2), fixture.members.getValue(7))
        assertEquals(listOf("failed:1:1", "completed:2:1"), fixture.outboxMarks)
    }

    @Test fun `claims use the retry delay, processing lease and attempt limit`() {
        val fixture = Fixture()
        fixture.service.execute(2)
        assertEquals(Claim(2, 1_000_000L - 60_000L, 1_000_000L - 600_000L, 5), fixture.claims.single())
    }

    @Test fun `readiness comes from the memory embedding`() {
        val fixture = Fixture()
        assertTrue(fixture.service.isReady())
        fixture.embeddingReady = false
        assertFalse(fixture.service.isReady())
    }

    @Test fun `queuing unassigned memories is delegated to the outbox`() {
        val fixture = Fixture()
        assertEquals(3, fixture.service.enqueueUnassigned())
    }

    @Test fun `moved centroids refresh group vectors before new topics are grouped`() {
        val fixture = Fixture()
        fixture.add(7, TopicCentroid.of(east) + north, 50, 51)
        val old = TopicEmbedding(PendingTopic(7, "1").embeddingKey(space), east)
        fixture.groupSnapshot = GroupSnapshot(listOf(StoredGroup(Group(3, setOf(7)), 5, east)), mapOf(7 to old),
            listOf(PendingTopic(7, "2")))
        fixture.vectors = mapOf("1" to TopicVector.normalized(listOf(-1.0, 0.0)))
        val result = fixture.service.execute(1)
        assertEquals(1, result.groupRefreshes.size)
        val change = fixture.groupWrites.single()
        assertEquals(mapOf(3 to 5L), change.expectedGroups)
        assertTrue(change.incomingTopicIds.isEmpty())
        assertEquals(PendingTopic(7, "2").embeddingKey(space), change.embeddings.getValue(7).key)
        assertEquals((TopicCentroid.of(east) + north).direction, change.embeddings.getValue(7).vector)
        assertEquals("groups", fixture.order.last())
    }

    private inner class Fixture {
        var vectors = emptyMap<String, TopicVector>()
        var groupSnapshot = GroupSnapshot(emptyList(), emptyMap())
        val centroids = linkedMapOf<Int, TopicCentroid>()
        val members = mutableMapOf<Int, MutableList<Int>>()
        var losesJoin = emptySet<Int>()
        val groupWrites = mutableListOf<GroupChange>()
        val order = mutableListOf<String>()
        val outboxMarks = mutableListOf<String>()
        val claims = mutableListOf<Claim>()
        var embeddingReady = true

        fun add(topicId: Int, centroid: TopicCentroid, vararg memoryIds: Int) {
            centroids[topicId] = centroid
            members[topicId] = memoryIds.toMutableList()
        }

        private val memoryCache = mutableMapOf<MemoryEmbeddingKey, MemoryEmbedding>()
        private val memoryEmbeddings = PrepareMemoryEmbedding(object : MemoryEmbeddingGenerator {
            override val space = this@OrganizePendingMemoriesTest.space
            override fun isAvailable() = embeddingReady
            override fun generate(content: String) = vectors[content] ?: east
        }, object : MemoryEmbeddingStore {
            override fun find(key: MemoryEmbeddingKey) = memoryCache[key]
            override fun save(embedding: MemoryEmbedding) = memoryCache.getOrPut(embedding.key) { embedding }
        })
        private val store = object : TopicMaintenanceStore {
            override fun centroids() = centroids.toMap()
            override fun read(topicIds: Collection<Int>) = topicIds.filter { it in centroids }
                .map { Topic(it, centroids.getValue(it), mapOf(MemoryAccess.PUBLIC to members.getValue(it).toList())) }
            override fun join(memoryId: Int, joins: List<TopicJoin>): Boolean {
                if (memoryId in losesJoin || joins.any { centroids[it.topicId] != it.expected }) return false
                joins.forEach {
                    centroids[it.topicId] = it.updated
                    members.getValue(it.topicId).add(memoryId)
                }
                return true
            }
            override fun create(memoryId: Int, centroid: TopicCentroid): Int {
                val id = 100 + centroids.keys.count { it >= 100 }
                add(id, centroid, memoryId)
                return id
            }
        }
        private val topicCache = mutableMapOf<TopicEmbeddingKey, TopicEmbedding>()
        private val topicEmbeddings = PrepareTopicEmbedding(TopicCentroidVectors(store, space), object : TopicEmbeddingStore {
            override fun find(key: TopicEmbeddingKey) = topicCache[key]
            override fun save(embedding: TopicEmbedding) = topicCache.getOrPut(embedding.key) { embedding }
        })
        private val groupReader = object : GroupSnapshotReader {
            override fun snapshot(): GroupSnapshot { order.add("refresh"); return groupSnapshot }
        }
        private val groupWriter = object : GroupChangeWriter {
            override fun apply(change: GroupChange): GroupWriteResult { groupWrites.add(change); return GroupWriteResult.Applied(listOf(3)) }
        }

        val service = OrganizePendingMemoriesService(
            object : TopicAssignmentOutbox {
                override fun claimReady(limit: Int, now: Long, retryBefore: Long, staleProcessingBefore: Long, maxAttempts: Int):
                    List<TopicAssignmentTask> {
                    claims.add(Claim(limit, retryBefore, staleProcessingBefore, maxAttempts))
                    return (1..3).take(limit).map { TopicAssignmentTask(it, 1, PendingMemory(it, "$it")) }
                }
                override fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long) =
                    outboxMarks.add("completed:$outboxId:$expectedAttempt")
                override fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long) =
                    outboxMarks.add("failed:$outboxId:$expectedAttempt")
                override fun enqueueUnassigned(now: Long) = 3
            },
            AssignMemoryToTopics(store, memoryEmbeddings),
            RefreshGroupedTopicEmbeddings(groupReader, groupWriter, topicEmbeddings),
            OrganizePendingTopics { order.add("groups"); GroupOrganizationResult.Completed(emptyList()) },
            Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC),
        )
    }

    private data class Claim(val limit: Int, val retryBefore: Long, val staleBefore: Long, val maxAttempts: Int)
}
