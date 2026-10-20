package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.OrganizePendingTopics
import com.homeassistant.application.port.input.topic.*
import com.homeassistant.application.port.output.group.*
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.application.usecase.group.PrepareTopicEmbedding
import com.homeassistant.application.usecase.group.RefreshGroupedTopicEmbeddings
import com.homeassistant.domain.group.*
import com.homeassistant.domain.topic.Topic
import com.homeassistant.domain.topic.TopicRelation
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class OrganizePendingMemoriesTest {
    private val space = EmbeddingSpace("test", "v1")
    private val east = TopicVector.normalized(listOf(1.0, 0.0))
    private val north = TopicVector.normalized(listOf(0.0, 1.0))
    private val northEast = TopicVector.normalized(listOf(1.0, 1.0))

    @Test fun `without topics the model creates one without any embedding work`() {
        val fixture = Fixture()
        fixture.decide = { TopicAssignmentDecision(emptyList(), listOf(NewTopic("보일러 관리", "수리"))) }
        val result = fixture.service.execute(1)
        assertEquals(MemoryAssignmentOutcome.Assigned(emptyList(), listOf(100)), result.memories.single().outcome)
        assertEquals(0, fixture.embedded)
        val batch = fixture.batches.single()
        assertEquals(listOf(TopicCreation("memory:1:0", "보일러 관리")), batch.creations)
        assertEquals(listOf(TopicMembershipAddition(TopicReference.Created("memory:1:0"), "수리", listOf(1))), batch.memberships)
    }

    @Test fun `only the nearest candidates are offered and a memory may join several of them`() {
        val fixture = Fixture(candidateLimit = 2)
        fixture.topics = listOf(TopicCandidate(7, "east"), TopicCandidate(8, "north"), TopicCandidate(9, "northEast"))
        fixture.vectors = mapOf("1" to east, "east" to east, "north" to north, "northEast" to northEast)
        fixture.decide = { request ->
            assertEquals(listOf(7, 9), request.candidates.map { it.topicId })
            TopicAssignmentDecision(listOf(TopicJoin(7, "수리", "보일러 관리와 수리"), TopicJoin(9, "지출", "주거비 지출")), emptyList())
        }
        val outcome = fixture.service.execute(1).memories.single().outcome
        assertEquals(MemoryAssignmentOutcome.Assigned(listOf(7, 9), emptyList()), outcome)
        val batch = fixture.batches.single()
        assertEquals(setOf(7, 9), batch.memberships.map { (it.topic as TopicReference.Existing).topicId }.toSet())
        assertEquals(listOf(TopicDescriptionUpdate(7, "보일러 관리와 수리"), TopicDescriptionUpdate(9, "주거비 지출")), batch.descriptionUpdates)
    }

    @Test fun `joining a topic that was not offered fails that memory only`() {
        val fixture = Fixture()
        fixture.topics = listOf(TopicCandidate(7, "east"))
        fixture.decide = { request ->
            if (request.memory.id == 1) TopicAssignmentDecision(listOf(TopicJoin(99, "x", "y")), emptyList())
            else TopicAssignmentDecision(listOf(TopicJoin(7, "x", "y")), emptyList())
        }
        val result = fixture.service.execute(2)
        assertIs<MemoryAssignmentOutcome.Failed>(result.memories.first().outcome)
        assertIs<MemoryAssignmentOutcome.Assigned>(result.memories.last().outcome)
        assertEquals(1, fixture.batches.size)
        assertEquals(listOf("failed:1:1", "completed:2:1"), fixture.outboxMarks)
    }

    @Test fun `claims use the retry delay, processing lease and attempt limit`() {
        val fixture = Fixture()
        fixture.service.execute(2)
        assertEquals(Claim(2, 1_000_000L - 60_000L, 1_000_000L - 600_000L, 5), fixture.claims.single())
    }

    @Test fun `readiness comes from the assignment services`() {
        val fixture = Fixture()
        assertTrue(fixture.service.isReady())
        fixture.modelReady = false
        assertFalse(fixture.service.isReady())
        fixture.modelReady = true
        fixture.embeddingReady = false
        assertFalse(fixture.service.isReady())
    }

    @Test fun `queuing unassigned memories is delegated to the outbox`() {
        val fixture = Fixture()
        assertEquals(3, fixture.service.enqueueUnassigned())
    }

    @Test fun `revised descriptions refresh group vectors before new topics are grouped`() {
        val fixture = Fixture()
        val old = TopicEmbedding(PendingTopic(7, "old").embeddingKey(space), east)
        fixture.groupSnapshot = GroupSnapshot(listOf(StoredGroup(Group(3, setOf(7)), 5, east)), mapOf(7 to old),
            listOf(PendingTopic(7, "new")))
        fixture.vectors = mapOf("new" to north)
        val result = fixture.service.execute(1)
        assertEquals(1, result.groupRefreshes.size)
        val change = fixture.groupWrites.single()
        assertEquals(mapOf(3 to 5L), change.expectedGroups)
        assertTrue(change.incomingTopicIds.isEmpty())
        assertEquals(PendingTopic(7, "new").embeddingKey(space), change.embeddings.getValue(7).key)
        assertEquals("groups", fixture.order.last())
    }

    private inner class Fixture(candidateLimit: Int = 5) {
        var topics = emptyList<TopicCandidate>()
        var vectors = emptyMap<String, TopicVector>()
        var decide: (TopicAssignmentRequest) -> TopicAssignmentDecision = { TopicAssignmentDecision(emptyList(), listOf(NewTopic("t", "c"))) }
        var groupSnapshot = GroupSnapshot(emptyList(), emptyMap())
        val batches = mutableListOf<TopicWriteBatch>()
        val groupWrites = mutableListOf<GroupChange>()
        val order = mutableListOf<String>()
        var embedded = 0
        val outboxMarks = mutableListOf<String>()
        val claims = mutableListOf<Claim>()
        var embeddingReady = true
        var modelReady = true

        private fun embed(text: String): TopicVector { embedded++; return vectors[text] ?: east }
        private val memoryCache = mutableMapOf<MemoryEmbeddingKey, MemoryEmbedding>()
        private val memoryEmbeddings = PrepareMemoryEmbedding(object : MemoryEmbeddingGenerator {
            override val space = this@OrganizePendingMemoriesTest.space
            override fun isAvailable() = embeddingReady
            override fun generate(content: String) = embed(content)
        }, object : MemoryEmbeddingStore {
            override fun find(key: MemoryEmbeddingKey) = memoryCache[key]
            override fun save(embedding: MemoryEmbedding) = memoryCache.getOrPut(embedding.key) { embedding }
        })
        private val topicCache = mutableMapOf<TopicEmbeddingKey, TopicEmbedding>()
        private val topicEmbeddings = PrepareTopicEmbedding(object : TopicEmbeddingGenerator {
            override val space = this@OrganizePendingMemoriesTest.space
            override fun generate(description: String) = embed(description)
        }, object : TopicEmbeddingStore {
            override fun find(key: TopicEmbeddingKey) = topicCache[key]
            override fun save(embedding: TopicEmbedding) = topicCache.getOrPut(embedding.key) { embedding }
        })
        private val store = object : TopicMaintenanceStore {
            override fun readForMaintenance(topicIds: Collection<Int>) = emptyList<Topic>()
            override fun relationsForMaintenance(topicIds: Collection<Int>) = emptySet<TopicRelation>()
            override fun apply(batch: TopicWriteBatch): TopicWriteResult {
                batches.add(batch)
                return TopicWriteResult(batch.creations.mapIndexed { index, creation -> creation.creationKey to 100 + index }.toMap())
            }
        }
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
            AssignMemoryToTopics({ topics }, memoryEmbeddings, topicEmbeddings, object : TopicAssignmentGenerator {
                override fun isAvailable() = modelReady
                override fun assign(request: TopicAssignmentRequest) = decide(request)
            }, store, candidateLimit),
            RefreshGroupedTopicEmbeddings(groupReader, groupWriter, topicEmbeddings),
            OrganizePendingTopics { order.add("groups"); GroupOrganizationResult.Completed(emptyList()) },
            Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC),
        )
    }

    private data class Claim(val limit: Int, val retryBefore: Long, val staleBefore: Long, val maxAttempts: Int)
}
