package com.homeassistant.application.usecase.group

import com.homeassistant.application.port.input.group.*
import com.homeassistant.application.port.output.group.*
import com.homeassistant.domain.group.*
import java.util.concurrent.CancellationException
import kotlin.test.*

class OrganizePendingTopicsTest {
    private val space = EmbeddingSpace("test", "v1")
    private val vector = TopicVector.normalized(listOf(1.0, 0.0))

    @Test fun `bounded batch continues after independent embedding failure and never writes failed topic`() {
        val fixture = Fixture()
        fixture.generate = { if (it == "1") error("unavailable") else vector }
        val result = assertIs<GroupOrganizationResult.Completed>(fixture.service.execute(2))
        assertEquals(listOf(1, 2), result.topics.map { it.topicId })
        assertIs<TopicOrganizationOutcome.Failed>(result.topics.first().outcome)
        assertIs<TopicOrganizationOutcome.Organized>(result.topics.last().outcome)
        assertEquals(listOf(setOf(2)), fixture.writes.map { it.incomingTopicIds })
    }

    @Test fun `conflict rereads snapshot and replans with a new key without regenerating embedding`() {
        val fixture = Fixture()
        fixture.write = { if (fixture.writes.size == 1) GroupWriteResult.Conflict else GroupWriteResult.Applied(listOf(10)) }
        val result = fixture.service.execute(1)
        assertIs<TopicOrganizationOutcome.Organized>(result.topics.single().outcome)
        assertEquals(2, fixture.reads)
        assertEquals(1, fixture.generated)
        assertEquals(2, fixture.writes.map { it.operationKey }.distinct().size)
    }

    @Test fun `conflicts are bounded and cached vectors survive retry calls`() {
        val fixture = Fixture()
        fixture.write = { GroupWriteResult.Conflict }
        repeat(2) { assertIs<TopicOrganizationOutcome.ConflictExhausted>(fixture.service.execute(1).topics.single().outcome) }
        assertEquals(6, fixture.writes.size)
        assertEquals(1, fixture.generated)
    }

    @Test fun `concurrent winner is observed after conflict`() {
        val fixture = Fixture()
        fixture.write = {
            val embedding = fixture.cache.values.single()
            fixture.snapshot = GroupSnapshot(listOf(StoredGroup(Group(4, setOf(1)), 1, vector)), mapOf(1 to embedding))
            GroupWriteResult.Conflict
        }
        assertIs<TopicOrganizationOutcome.AlreadyOrganized>(fixture.service.execute(1).topics.single().outcome)
        assertEquals(1, fixture.writes.size)
    }

    @Test fun `model change stops batch before any embedding or write`() {
        val fixture = Fixture()
        val embedding = TopicEmbedding(PendingTopic(9, "9").embeddingKey(EmbeddingSpace("old", "v1")), vector)
        fixture.snapshot = GroupSnapshot(listOf(StoredGroup(Group(4, setOf(9)), 1, vector)), mapOf(9 to embedding))
        assertIs<GroupOrganizationResult.RebuildRequired>(fixture.service.execute(2))
        assertEquals(0, fixture.generated)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun `dimension change stops batch and cancellation propagates`() {
        val fixture = Fixture()
        fixture.snapshot = GroupSnapshot(listOf(StoredGroup(Group(4, setOf(9)), 1, vector)),
            mapOf(9 to TopicEmbedding(PendingTopic(9, "9").embeddingKey(space), vector)))
        fixture.generate = { TopicVector.normalized(listOf(1.0)) }
        assertIs<GroupOrganizationResult.RebuildRequired>(fixture.service.execute(1))
        val cancelled = Fixture()
        cancelled.generate = { throw CancellationException("cancel") }
        assertFailsWith<CancellationException> { cancelled.service.execute(1) }
    }

    @Test fun `attachment and split are one change preserving the old group id`() {
        val fixture = Fixture(maxTopics = 2)
        fixture.snapshot = GroupSnapshot(listOf(StoredGroup(Group(4, setOf(8, 9)), 7, vector)),
            (8..9).associateWith { TopicEmbedding(PendingTopic(it, "$it").embeddingKey(space), vector) })
        val result = fixture.service.execute(1)
        assertIs<GroupSplit.Divided>(assertIs<TopicOrganizationOutcome.Organized>(result.topics.single().outcome).split)
        val change = fixture.writes.single()
        assertEquals(mapOf(4 to 7L), change.expectedGroups)
        assertEquals(setOf(1, 8, 9), change.resultingGroups.flatMap { it.topicIds }.toSet())
        assertEquals(GroupTarget.Existing(4), change.resultingGroups.first { 1 in it.topicIds }.target)
        assertEquals(2, change.resultingGroups.size)
    }

    private inner class Fixture(maxTopics: Int = 12) {
        var snapshot = GroupSnapshot(emptyList(), emptyMap())
        val cache = mutableMapOf<TopicEmbeddingKey, TopicEmbedding>()
        val writes = mutableListOf<GroupChange>()
        var reads = 0
        var generated = 0
        var generate: (String) -> TopicVector = { vector }
        var write: (GroupChange) -> GroupWriteResult = { GroupWriteResult.Applied(listOf(10)) }
        private val generator = object : TopicEmbeddingGenerator {
            override val space = this@OrganizePendingTopicsTest.space
            override fun generate(description: String): TopicVector { generated++; return generate.invoke(description) }
        }
        private val store = object : TopicEmbeddingStore {
            override fun find(key: TopicEmbeddingKey) = cache[key]
            override fun save(embedding: TopicEmbedding) = cache.getOrPut(embedding.key) { embedding }
        }
        private val reader = object : GroupSnapshotReader {
            override fun snapshot(): GroupSnapshot { reads++; return snapshot }
        }
        private val writer = object : GroupChangeWriter {
            override fun apply(change: GroupChange): GroupWriteResult { writes.add(change); return write(change) }
        }
        val service = OrganizePendingTopicsService(object : PendingTopicSource {
            override fun pending(limit: Int) = (1..3).take(limit).map { PendingTopic(it, "$it") }
        }, OrganizeTopic(reader, writer, PrepareTopicEmbedding(generator, store),
            GroupOrganizationPlanner(GroupPlacementPolicy(GroupPolicySettings(maxTopics = maxTopics)),
                GroupSplitPolicy(GroupPolicySettings(maxTopics = maxTopics)))))
    }
}
