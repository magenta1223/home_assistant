package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.OrganizePendingTopics
import com.homeassistant.application.port.input.topic.*
import com.homeassistant.application.port.output.group.*
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.application.usecase.group.PrepareTopicEmbedding
import com.homeassistant.application.usecase.group.RefreshGroupedTopicEmbeddings
import com.homeassistant.domain.group.*
import kotlin.test.*

class OrganizePendingMemoriesTest {
    private val space = EmbeddingSpace("test", "v1")
    private val east = TopicVector.normalized(listOf(1.0, 0.0))
    private val north = TopicVector.normalized(listOf(0.0, 1.0))

    @Test fun `nearby memory attaches to the nearest topic and a distant one creates a topic`() {
        val fixture = Fixture()
        fixture.snapshot = snapshotOf(OrganizedTopic(4, setOf(9), 2, east), 9 to east)
        fixture.vectors = mapOf("1" to east, "2" to north)
        val result = assertIs<MemoryOrganizationResult.Completed>(fixture.service.execute(2))
        assertEquals(listOf(1, 2), result.memories.map { it.memoryId })
        val (attach, create) = fixture.writes
        assertEquals(mapOf(4 to 2L), attach.expectedTopics)
        assertEquals(listOf(ResultingTopic(TopicTarget.Existing(4), setOf(1, 9), east)), attach.resultingTopics)
        assertEquals(emptyMap(), create.expectedTopics)
        assertEquals(TopicTarget.Created, create.resultingTopics.single().target)
    }

    @Test fun `oversized topic splits in one change that keeps the old topic id`() {
        val fixture = Fixture(maxMembers = 2)
        fixture.snapshot = snapshotOf(OrganizedTopic(4, setOf(8, 9), 7, east), 8 to east, 9 to east)
        fixture.vectors = mapOf("1" to east)
        val outcome = fixture.service.execute(1).memories.single().outcome
        assertIs<GroupSplit.Divided>(assertIs<MemoryOrganizationOutcome.Organized>(outcome).split)
        val change = fixture.writes.single()
        assertEquals(setOf(1, 8, 9), change.resultingTopics.flatMap { it.memoryIds }.toSet())
        assertEquals(TopicTarget.Existing(4), change.resultingTopics.first().target)
        assertEquals(listOf(TopicTarget.Created), change.resultingTopics.drop(1).map { it.target })
    }

    @Test fun `conflict replans from a fresh snapshot without embedding the memory again`() {
        val fixture = Fixture()
        fixture.write = { if (fixture.writes.size == 1) TopicOrganizationWriteResult.Conflict else TopicOrganizationWriteResult.Applied(listOf(5)) }
        assertIs<MemoryOrganizationOutcome.Organized>(fixture.service.execute(1).memories.single().outcome)
        assertEquals(2, fixture.writes.size)
        assertEquals(1, fixture.generated)
    }

    @Test fun `failed memory does not stop the batch and model change stops before later stages`() {
        val fixture = Fixture()
        fixture.generate = { if (it == "1") error("unavailable") else east }
        val result = assertIs<MemoryOrganizationResult.Completed>(fixture.service.execute(2))
        assertIs<MemoryOrganizationOutcome.Failed>(result.memories.first().outcome)
        assertIs<MemoryOrganizationOutcome.Organized>(result.memories.last().outcome)

        val changed = Fixture()
        changed.snapshot = snapshotOf(OrganizedTopic(4, setOf(9), 1, east), 9 to east, space = EmbeddingSpace("old", "v1"))
        assertIs<MemoryOrganizationResult.RebuildRequired>(changed.service.execute(2))
        assertEquals(0, changed.generated)
        assertEquals(0, changed.groupRuns)
    }

    @Test fun `descriptions are summarized per topic and superseded or failed summaries are reported`() {
        val fixture = Fixture()
        fixture.descriptionRequests = listOf(
            TopicDescriptionRequest(1, 3, listOf("보일러 점검")),
            TopicDescriptionRequest(2, 1, listOf("화분 물주기")),
            TopicDescriptionRequest(3, 1, listOf("실패")),
        )
        fixture.describe = { contents -> if (contents == listOf("실패")) error("model down") else "요약: ${contents.single()}" }
        fixture.describeWrite = { topicId, _, _ -> if (topicId == 2) TopicDescriptionWriteResult.Conflict else TopicDescriptionWriteResult.Applied }
        val result = assertIs<MemoryOrganizationResult.Completed>(fixture.service.execute(1))
        assertEquals(listOf(
            TopicDescriptionOutcome.Described(1),
            TopicDescriptionOutcome.Superseded(2),
            TopicDescriptionOutcome.Failed(3, "model down"),
        ), result.descriptions)
        assertEquals(listOf(Triple(1, 3L, "요약: 보일러 점검"), Triple(2, 1L, "요약: 화분 물주기")), fixture.descriptions)
        assertEquals(1, fixture.groupRuns)
    }

    @Test fun `grouped topic with a new description is re-embedded in place without moving`() {
        val fixture = Fixture()
        val old = TopicEmbedding(PendingTopic(7, "old").embeddingKey(space), east)
        val other = TopicEmbedding(PendingTopic(8, "other").embeddingKey(space), east)
        fixture.groupSnapshot = GroupSnapshot(listOf(StoredGroup(Group(3, setOf(7, 8)), 5, east)),
            mapOf(7 to old, 8 to other), listOf(PendingTopic(7, "new")))
        fixture.vectors = mapOf("new" to north)
        val result = assertIs<MemoryOrganizationResult.Completed>(fixture.service.execute(1))
        assertEquals(1, result.groupRefreshes.size)
        val change = fixture.groupWrites.single()
        assertEquals(mapOf(3 to 5L), change.expectedGroups)
        assertTrue(change.incomingTopicIds.isEmpty())
        assertEquals(setOf(7, 8), change.resultingGroups.single().topicIds)
        assertEquals(PendingTopic(7, "new").embeddingKey(space), change.embeddings.getValue(7).key)
        assertEquals(other, change.embeddings.getValue(8))
    }

    private fun snapshotOf(topic: OrganizedTopic, vararg vectors: Pair<Int, TopicVector>, space: EmbeddingSpace = this.space) =
        TopicOrganizationSnapshot(listOf(topic), vectors.associate { (id, vector) ->
            id to MemoryEmbedding(PendingMemory(id, "$id").embeddingKey(space), vector)
        })

    private inner class Fixture(maxMembers: Int = 12) {
        var snapshot = TopicOrganizationSnapshot(emptyList(), emptyMap())
        var groupSnapshot = GroupSnapshot(emptyList(), emptyMap())
        var vectors = emptyMap<String, TopicVector>()
        var generate: (String) -> TopicVector = { vectors[it] ?: east }
        var write: (TopicOrganizationChange) -> TopicOrganizationWriteResult = { TopicOrganizationWriteResult.Applied(listOf(5)) }
        var descriptionRequests = emptyList<TopicDescriptionRequest>()
        var describe: (List<String>) -> String = { "summary" }
        var describeWrite: (Int, Long, String) -> TopicDescriptionWriteResult = { _, _, _ -> TopicDescriptionWriteResult.Applied }
        val writes = mutableListOf<TopicOrganizationChange>()
        val groupWrites = mutableListOf<GroupChange>()
        val descriptions = mutableListOf<Triple<Int, Long, String>>()
        var generated = 0
        var groupRuns = 0

        private val memoryGenerator = object : MemoryEmbeddingGenerator {
            override val space = this@OrganizePendingMemoriesTest.space
            override fun generate(content: String): TopicVector { generated++; return generate.invoke(content) }
        }
        private val memoryCache = mutableMapOf<MemoryEmbeddingKey, MemoryEmbedding>()
        private val memoryStore = object : MemoryEmbeddingStore {
            override fun find(key: MemoryEmbeddingKey) = memoryCache[key]
            override fun save(embedding: MemoryEmbedding) = memoryCache.getOrPut(embedding.key) { embedding }
        }
        private val writer = object : TopicOrganizationWriter {
            override fun apply(change: TopicOrganizationChange): TopicOrganizationWriteResult { writes.add(change); return write(change) }
            override fun describe(topicId: Int, revision: Long, description: String): TopicDescriptionWriteResult {
                descriptions.add(Triple(topicId, revision, description))
                return describeWrite(topicId, revision, description)
            }
        }
        private val topicGenerator = object : TopicEmbeddingGenerator {
            override val space = this@OrganizePendingMemoriesTest.space
            override fun generate(description: String) = generate.invoke(description)
        }
        private val topicCache = mutableMapOf<TopicEmbeddingKey, TopicEmbedding>()
        private val topicStore = object : TopicEmbeddingStore {
            override fun find(key: TopicEmbeddingKey) = topicCache[key]
            override fun save(embedding: TopicEmbedding) = topicCache.getOrPut(embedding.key) { embedding }
        }
        private val groupWriter = object : GroupChangeWriter {
            override fun apply(change: GroupChange): GroupWriteResult { groupWrites.add(change); return GroupWriteResult.Applied(listOf(3)) }
        }
        private val settings = GroupPolicySettings(maxTopics = maxMembers)

        val service = OrganizePendingMemoriesService(
            object : PendingMemorySource {
                override fun pending(limit: Int) = (1..3).take(limit).map { PendingMemory(it, "$it") }
            },
            OrganizeMemory(object : TopicOrganizationSnapshotReader { override fun snapshot() = snapshot }, writer, PrepareMemoryEmbedding(memoryGenerator, memoryStore),
                GroupOrganizationPlanner(GroupPlacementPolicy(settings), GroupSplitPolicy(settings))),
            DescribeOrganizedTopics(object : PendingTopicDescriptionSource {
                override fun pendingDescriptions(limit: Int) = descriptionRequests
            }, { describe(it) }, writer),
            RefreshGroupedTopicEmbeddings(object : GroupSnapshotReader { override fun snapshot() = groupSnapshot }, groupWriter, PrepareTopicEmbedding(topicGenerator, topicStore)),
            OrganizePendingTopics { groupRuns++; GroupOrganizationResult.Completed(emptyList()) },
        )
    }
}
