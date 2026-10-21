package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicAssignmentOutboxTable
import com.homeassistant.adapter.outbound.persistence.repo.group.GroupRepository
import com.homeassistant.adapter.outbound.persistence.repo.group.TopicEmbeddingRepository
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.input.topic.MemoryAssignmentOutcome
import com.homeassistant.application.port.output.group.*
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.application.usecase.group.*
import com.homeassistant.application.usecase.topic.*
import com.homeassistant.domain.group.*
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.topic.TopicCentroid
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.*

class MemoryTopicAssignmentTest {
    private val space = EmbeddingSpace("test", "passage:v1")
    private val east = TopicVector.normalized(listOf(1.0, 0.0))
    private val north = TopicVector.normalized(listOf(0.0, 1.0))
    private val nearEast = TopicVector.normalized(listOf(1.0, 0.2))
    private val user = UserId("owner")

    @Test fun `near memories share a topic, a far one starts another and groups follow`() = withDatabase { db, path ->
        val (boiler, rent) = seedMemories(db, "first", "보일러 점검 예약", "월세 이체일은 25일")
        val vectors = mutableMapOf("보일러 점검 예약" to east, "월세 이체일은 25일" to north)
        val first = service(db, vectors).execute(10)
        assertEquals(listOf(true, true), first.memories.map { (it.outcome as MemoryAssignmentOutcome.Assigned).created })
        val topics = TopicRepository(db)
        val (boilerTopic, housingTopic) = topics.centroids().keys.toList()
        assertEquals(setOf(boilerTopic, housingTopic), GroupRepository(db).snapshot().groups.flatMap { it.group.topicIds }.toSet())

        val (repair) = seedMemories(db, "second", "보일러 수리비 12만 원")
        vectors["보일러 수리비 12만 원"] = nearEast
        val result = service(db, vectors).execute(10)

        assertEquals(MemoryAssignmentOutcome.Assigned(listOf(boilerTopic), created = false), result.memories.single().outcome)
        val stored = topics.read(listOf(boilerTopic, housingTopic)).associateBy { it.id }
        val access = MemoryAccess.restricted(listOf(user))
        assertEquals(mapOf(access to listOf(boiler, repair)), stored.getValue(boilerTopic).slices)
        assertEquals(mapOf(access to listOf(rent)), stored.getValue(housingTopic).slices)
        assertEquals(TopicCentroid.of(east) + nearEast, stored.getValue(boilerTopic).centroid)
        assertTrue(result.groupRefreshes.isNotEmpty())
        assertTrue(GroupRepository(db).snapshot().topicsWithOutdatedGroupVector.isEmpty())

        val restarted = DatabaseFactory.init(path)
        assertTrue(service(restarted, vectors).execute(10).memories.isEmpty())
    }

    @Test fun `a failed write records the attempt and the retry creates the topic once`() = withDatabase { db, _ ->
        val (memory) = seedMemories(db, "first", "화분 물주기")
        val vectors = mapOf("화분 물주기" to east)
        val failing = service(db, vectors, store = object : TopicMaintenanceStore by TopicRepository(db) {
            override fun create(memoryId: Int, centroid: TopicCentroid): Int = error("disk full")
        })
        assertIs<MemoryAssignmentOutcome.Failed>(failing.execute(10).memories.single().outcome)
        assertEquals(OutboxRow("FAILED", 1, "disk full"), outboxRow(db, memory))

        service(db, vectors).execute(10)
        assertEquals(1, TopicRepository(db).centroids().size)
        assertEquals(OutboxRow("COMPLETED", 2, null), outboxRow(db, memory))
    }

    private fun seedMemories(db: Database, key: String, vararg contents: String): List<Int> {
        val record = SourceRecordRepositoryImpl(db).saveAll(SourceDescriptor("test", key), listOf(SourceRecordDraft(key, contents.joinToString())),
            MemoryAccess.restricted(listOf(user))).recordsToAnalyze.single()
        return MemoryRepository(db).commit(user, contents.mapIndexed { index, content ->
            IdempotentMemoryProposal("$key-$index", MemoryProposal(content, listOf(record.id)))
        }, listOf(record.id)).map { it.id }
    }

    private fun service(
        db: Database,
        vectors: Map<String, TopicVector>,
        store: TopicMaintenanceStore = TopicRepository(db),
    ): OrganizePendingMemoriesService {
        val groups = GroupRepository(db)
        val memoryEmbeddings = PrepareMemoryEmbedding(object : MemoryEmbeddingGenerator {
            override val space = this@MemoryTopicAssignmentTest.space
            override fun isAvailable() = true
            override fun generate(content: String) = vectors.getValue(content)
        }, MemoryEmbeddingRepository(db))
        val topicEmbeddings = PrepareTopicEmbedding(TopicCentroidVectors(TopicRepository(db), space), TopicEmbeddingRepository(db))
        val settings = GroupPolicySettings()
        return OrganizePendingMemoriesService(
            TopicAssignmentOutboxRepository(db),
            AssignMemoryToTopics(store, memoryEmbeddings),
            RefreshGroupedTopicEmbeddings(groups, groups, topicEmbeddings),
            OrganizePendingTopicsService(groups, OrganizeTopic(groups, groups, topicEmbeddings,
                GroupOrganizationPlanner(GroupPlacementPolicy(settings), GroupSplitPolicy(settings)))),
            MergeSmallGroups(groups, groups, GroupMergePolicy(settings)),
            retryDelayMillis = 0,
        )
    }

    private data class OutboxRow(val status: String, val attempts: Int, val lastError: String?)

    private fun outboxRow(db: Database, memoryId: Int): OutboxRow? = transaction(db) {
        TopicAssignmentOutboxTable.selectAll().where { TopicAssignmentOutboxTable.memoryId eq memoryId }.singleOrNull()?.let {
            OutboxRow(it[TopicAssignmentOutboxTable.status], it[TopicAssignmentOutboxTable.attempts], it[TopicAssignmentOutboxTable.lastError])
        }
    }

    private fun withDatabase(block: (Database, String) -> Unit) {
        val path = Files.createTempFile("memory-topic-assignment", ".db")
        try { block(DatabaseFactory.init(path.toString()), path.toString()) }
        finally { Files.deleteIfExists(path) }
    }
}
