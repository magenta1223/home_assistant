package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
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
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import kotlin.test.*

class MemoryTopicAssignmentTest {
    private val space = EmbeddingSpace("test", "passage:v1")
    private val east = TopicVector.normalized(listOf(1.0, 0.0))
    private val user = UserId("owner")

    @Test fun `a memory joins several topics, revises their descriptions and groups follow`() = withDatabase { db, path ->
        val (boiler, rent) = seedMemories(db, "first", "보일러 점검 예약", "월세 이체일은 25일")
        val decisions = ArrayDeque(listOf(
            TopicAssignmentDecision(emptyList(), listOf(NewTopic("보일러 관리", "점검"))),
            TopicAssignmentDecision(emptyList(), listOf(NewTopic("주거비", "월세"))),
        ))
        val first = service(db) { decisions.removeFirst() }.execute(10)
        assertTrue(first.memories.all { it.outcome is MemoryAssignmentOutcome.Assigned })
        val topics = TopicRepository(db)
        val (boilerTopic, housingTopic) = topics.topics().map { it.topicId }
        assertEquals(setOf(boilerTopic, housingTopic), GroupRepository(db).snapshot().groups.flatMap { it.group.topicIds }.toSet())

        val (repair) = seedMemories(db, "second", "보일러 수리비 12만 원")
        val result = service(db) { request ->
            assertEquals(setOf(boilerTopic, housingTopic), request.candidates.map { it.topicId }.toSet())
            TopicAssignmentDecision(listOf(
                TopicJoin(boilerTopic, "수리", "보일러 점검과 수리 기록"),
                TopicJoin(housingTopic, "비정기 지출", "월세와 수리비 같은 주거 지출"),
            ), emptyList())
        }.execute(10)

        assertEquals(MemoryAssignmentOutcome.Assigned(listOf(boilerTopic, housingTopic), emptyList()), result.memories.single().outcome)
        val stored = topics.readForMaintenance(listOf(boilerTopic, housingTopic)).associateBy { it.id }
        assertEquals(mapOf("점검" to listOf(boiler), "수리" to listOf(repair)), stored.getValue(boilerTopic).memories)
        assertEquals(mapOf("월세" to listOf(rent), "비정기 지출" to listOf(repair)), stored.getValue(housingTopic).memories)
        assertEquals("보일러 점검과 수리 기록", stored.getValue(boilerTopic).description)
        assertTrue(result.groupRefreshes.isNotEmpty())
        assertTrue(GroupRepository(db).snapshot().staleTopics.isEmpty())

        val restarted = DatabaseFactory.init(path)
        assertTrue(TopicRepository(restarted).pending(10).isEmpty())
    }

    @Test fun `a failed write leaves the memory pending and the retry creates the topic once`() = withDatabase { db, _ ->
        seedMemories(db, "first", "화분 물주기")
        val decision = TopicAssignmentDecision(emptyList(), listOf(NewTopic("식물 관리", "물주기")))
        val failing = service(db, store = object : TopicMaintenanceStore by TopicRepository(db) {
            override fun apply(batch: TopicWriteBatch): TopicWriteResult = error("disk full")
        }) { decision }
        assertIs<MemoryAssignmentOutcome.Failed>(failing.execute(10).memories.single().outcome)
        assertEquals(1, TopicRepository(db).pending(10).size)

        service(db) { decision }.execute(10)
        assertEquals(listOf("식물 관리"), TopicRepository(db).topics().map { it.description })
        assertTrue(TopicRepository(db).pending(10).isEmpty())
    }

    @Test fun `description update for a missing topic rolls back the whole batch`() = withDatabase { db, _ ->
        val (memory) = seedMemories(db, "first", "a")
        val topics = TopicRepository(db)
        assertFailsWith<IllegalArgumentException> {
            topics.apply(TopicWriteBatch(
                creations = listOf(TopicCreation("k", "d")),
                memberships = listOf(TopicMembershipAddition(TopicReference.Created("k"), "c", listOf(memory))),
                descriptionUpdates = listOf(TopicDescriptionUpdate(999, "x")),
            ))
        }
        assertTrue(topics.topics().isEmpty())
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
        store: TopicMaintenanceStore = TopicRepository(db),
        decide: (TopicAssignmentRequest) -> TopicAssignmentDecision,
    ): OrganizePendingMemoriesService {
        val topics = TopicRepository(db)
        val groups = GroupRepository(db)
        val memoryEmbeddings = PrepareMemoryEmbedding(object : MemoryEmbeddingGenerator {
            override val space = this@MemoryTopicAssignmentTest.space
            override fun generate(content: String) = east
        }, MemoryEmbeddingRepository(db))
        val topicEmbeddings = PrepareTopicEmbedding(object : TopicEmbeddingGenerator {
            override val space = this@MemoryTopicAssignmentTest.space
            override fun generate(description: String) = east
        }, TopicEmbeddingRepository(db))
        val settings = GroupPolicySettings()
        return OrganizePendingMemoriesService(
            topics,
            AssignMemoryToTopics(topics, memoryEmbeddings, topicEmbeddings, decide, store),
            RefreshGroupedTopicEmbeddings(groups, groups, topicEmbeddings),
            OrganizePendingTopicsService(groups, OrganizeTopic(groups, groups, topicEmbeddings,
                GroupOrganizationPlanner(GroupPlacementPolicy(settings), GroupSplitPolicy(settings)))),
        )
    }

    private fun withDatabase(block: (Database, String) -> Unit) {
        val path = Files.createTempFile("memory-topic-assignment", ".db")
        try { block(DatabaseFactory.init(path.toString()), path.toString()) }
        finally { Files.deleteIfExists(path) }
    }
}
