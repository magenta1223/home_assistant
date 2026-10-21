package com.homeassistant.adapter.outbound.persistence.repo.group

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.adapter.outbound.persistence.repo.topic.TopicRepository
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.group.*
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.application.port.input.group.*
import com.homeassistant.application.usecase.group.*
import com.homeassistant.domain.group.*
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.topic.TopicCentroid
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class GroupRepositoryTest {
    private val space = EmbeddingSpace("test", "passage:v1")
    private val vector = TopicVector.normalized(listOf(1.0, 0.0))

    @Test fun `create attach split restart and repeat preserve all topic state`() = withDatabase { db, path ->
        val sources = SourceRecordRepositoryImpl(db)
        val memories = MemoryRepository(db)
        val source = SourceDescriptor("test", "private")
        val user = UserId("owner")
        val record = sources.saveAll(source, listOf(SourceRecordDraft("test", "original")), MemoryAccess.restricted(listOf(user)))
            .recordsToAnalyze.single()
        val committed = memories.commit(user, (1..5).map { IdempotentMemoryProposal("m$it", MemoryProposal("fact $it", listOf(record.id))) },
            listOf(record.id))
        val topics = TopicRepository(db)
        val ids = committed.map { topics.create(it.id, TopicCentroid.of(vector)) }.toSet()
        val beforeTopics = topics.read(ids)
        val beforeMemories = memories.getMemories(user)
        val beforeSources = sources.findBySource(source)
        val first = service(db, 2).execute(3)
        assertEquals(3, first.topics.size)
        assertTrue(first.topics.all { it.outcome is TopicOrganizationOutcome.Organized })
        assertTrue(first.topics.any { (it.outcome as TopicOrganizationOutcome.Organized).split is GroupSplit.Divided })
        val restarted = DatabaseFactory.init(path)
        val remaining = service(restarted, 2).execute(10)
        assertEquals(2, remaining.topics.size)
        assertTrue(remaining.topics.all { it.outcome is TopicOrganizationOutcome.Organized })
        assertTrue(service(restarted, 2).execute(10).topics.isEmpty())
        val snapshot = GroupRepository(restarted).snapshot()
        assertEquals(ids, snapshot.groups.flatMap { it.group.topicIds }.toSet())
        assertEquals(ids.size, snapshot.groups.sumOf { it.group.topicIds.size })
        assertTrue(snapshot.groups.all { it.group.topicIds.size <= 2 })
        assertEquals(snapshot.groups, GroupRepository(restarted).groups())
        assertEquals(beforeTopics, topics.read(ids))
        assertEquals(beforeMemories, memories.getMemories(user))
        assertEquals(beforeSources, sources.findBySource(source))
    }

    @Test fun `receipt returns original ids before revision validation and rejects changed payload`() = withDatabase { db, _ ->
        val embeddings = seed(db, 2)
        val repository = GroupRepository(db)
        val create = change("create", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings)
        val id = assertIs<GroupWriteResult.Applied>(repository.apply(create)).groupIds.single()
        assertEquals(GroupWriteResult.AlreadyApplied(listOf(id)), repository.apply(create))
        repository.apply(change("attach", mapOf(id to 1), setOf(2), listOf(setOf(1, 2)), embeddings, id))
        assertEquals(GroupWriteResult.AlreadyApplied(listOf(id)), repository.apply(create))
        assertFailsWith<IllegalArgumentException> { repository.apply(create.copy(incomingTopicIds = setOf(2))) }
        assertEquals(setOf(1, 2), repository.snapshot().groups.single().group.topicIds)
    }

    @Test fun `stale revision and already assigned incoming reject without reserving operation keys`() = withDatabase { db, _ ->
        val embeddings = seed(db, 2)
        val repository = GroupRepository(db)
        val id = assertIs<GroupWriteResult.Applied>(repository.apply(change("a", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings))).groupIds.single()
        val stale = change("b", mapOf(id to 9), setOf(2), listOf(setOf(1, 2)), embeddings, id)
        assertEquals(GroupWriteResult.Conflict, repository.apply(stale))
        assertEquals(GroupWriteResult.Conflict, repository.apply(change("c", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings)))
        assertEquals(1L, transaction(db) { GroupReceiptTable.selectAll().count() })
        assertIs<GroupWriteResult.Applied>(repository.apply(stale.copy(expectedGroups = mapOf(id to 1))))
    }

    @Test fun `merge storage contract replaces multiple groups atomically and preserves retained id`() = withDatabase { db, _ ->
        val embeddings = seed(db, 2)
        val repository = GroupRepository(db)
        val first = assertIs<GroupWriteResult.Applied>(repository.apply(change("a", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings))).groupIds.single()
        val second = assertIs<GroupWriteResult.Applied>(repository.apply(change("b", incoming = setOf(2), parts = listOf(setOf(2)), embeddings = embeddings))).groupIds.single()
        val merge = change("merge", mapOf(first to 1, second to 1), emptySet(), listOf(setOf(1, 2)), embeddings, first)
        assertEquals(GroupWriteResult.Applied(listOf(first)), repository.apply(merge))
        val group = repository.snapshot().groups.single()
        assertEquals(Group(first, setOf(1, 2)), group.group)
        assertEquals(2L, group.revision)
        assertEquals(GroupWriteResult.AlreadyApplied(listOf(first)), repository.apply(merge))
    }

    @Test fun `a single-topic group merges into its neighbor and is deleted`() = withDatabase { db, _ ->
        val embeddings = seed(db, 3)
        val repository = GroupRepository(db)
        val pair = assertIs<GroupWriteResult.Applied>(repository.apply(change("a", incoming = setOf(1, 2), parts = listOf(setOf(1, 2)), embeddings = embeddings))).groupIds.single()
        val single = assertIs<GroupWriteResult.Applied>(repository.apply(change("b", incoming = setOf(3), parts = listOf(setOf(3)), embeddings = embeddings))).groupIds.single()

        val outcomes = MergeSmallGroups(repository, repository, GroupMergePolicy(GroupPolicySettings())).execute()

        assertEquals(listOf(GroupMergeOutcome.Merged(pair, single)), outcomes)
        assertEquals(listOf(Group(pair, setOf(1, 2, 3))), repository.snapshot().groups.map { it.group })
    }

    @Test fun `missing stolen omitted topics and incorrect centroid roll back receipt and groups`() = withDatabase { db, _ ->
        val embeddings = seed(db, 3)
        val repository = GroupRepository(db)
        val id = assertIs<GroupWriteResult.Applied>(repository.apply(change("a", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings))).groupIds.single()
        val invalid = listOf(
            change("omit", mapOf(id to 1), setOf(2), listOf(setOf(2)), embeddings, id),
            change("steal", incoming = setOf(2), parts = listOf(setOf(1, 2)), embeddings = embeddings),
            change("extra", incoming = setOf(2), parts = listOf(setOf(2, 3)), embeddings = embeddings),
            change("wrong-centroid", incoming = setOf(2), parts = listOf(setOf(2)), embeddings = embeddings).let {
                it.copy(resultingGroups = it.resultingGroups.map { result -> result.copy(centroid = TopicVector.normalized(listOf(0.0, 1.0))) })
            },
            change("missing", incoming = setOf(999), parts = listOf(setOf(999)), embeddings =
                mapOf(999 to TopicEmbedding(PendingTopic(999, "1").embeddingKey(space), vector))),
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { repository.apply(it) } }
        assertEquals(setOf(1), repository.snapshot().groups.single().group.topicIds)
        assertEquals(1L, transaction(db) { GroupReceiptTable.selectAll().count() })
    }

    @Test fun `failure during membership replacement rolls back revision centroid and receipt`() = withDatabase { db, _ ->
        val embeddings = seed(db, 2)
        val repository = GroupRepository(db)
        val id = assertIs<GroupWriteResult.Applied>(repository.apply(change("a", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings))).groupIds.single()
        val before = repository.snapshot()
        transaction(db) { exec("CREATE TRIGGER fail_group BEFORE INSERT ON topic_group_memberships BEGIN SELECT RAISE(ABORT, 'forced'); END") }
        val attach = change("b", mapOf(id to 1), setOf(2), listOf(setOf(1, 2)), embeddings, id)
        assertFails { repository.apply(attach) }
        assertEquals(before, repository.snapshot())
        assertEquals(1L, transaction(db) { GroupReceiptTable.selectAll().count() })
        transaction(db) { exec("DROP TRIGGER fail_group") }
        assertIs<GroupWriteResult.Applied>(repository.apply(attach))
    }

    @Test fun `concurrent assignment of one topic has one winner and one conflict`() = withDatabase { db, _ ->
        val embeddings = seed(db, 1)
        val repository = GroupRepository(db)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { attempt -> pool.submit<GroupWriteResult> {
                start.await()
                repository.apply(change("race-$attempt", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings))
            } }
            start.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is GroupWriteResult.Applied })
            assertEquals(1, results.count { it is GroupWriteResult.Conflict })
            assertEquals(1, repository.snapshot().groups.size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `embedding cache survives restart and group model mixing requires rebuild`() = withDatabase { db, path ->
        val embeddings = seed(db, 2)
        val repository = GroupRepository(db)
        repository.apply(change("a", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings))
        val restarted = TopicEmbeddingRepository(DatabaseFactory.init(path))
        assertEquals(embeddings.getValue(1), restarted.find(embeddings.getValue(1).key))
        // The first vector for a cache key wins even if another embedding request returns different coordinates.
        assertEquals(embeddings.getValue(1), restarted.save(embeddings.getValue(1).copy(vector = TopicVector.normalized(listOf(0.0, 1.0)))))
        val replacement = restarted.save(embeddings.getValue(2).let { it.copy(key = it.key.copy(space = EmbeddingSpace("new", "v1"))) })
        assertFailsWith<GroupRebuildRequired> {
            repository.apply(change("mixed", incoming = setOf(2), parts = listOf(setOf(2)), embeddings = mapOf(2 to replacement)))
        }
        assertEquals(1, repository.snapshot().groups.size)
    }

    private fun seed(db: Database, count: Int): Map<Int, TopicEmbedding> {
        val user = UserId("owner")
        val record = SourceRecordRepositoryImpl(db).saveAll(SourceDescriptor("test", "seed"), listOf(SourceRecordDraft("seed", "seed")),
            MemoryAccess.restricted(listOf(user))).recordsToAnalyze.single()
        val memories = MemoryRepository(db).commit(user, (1..count).map { IdempotentMemoryProposal("seed-$it", MemoryProposal("fact $it", listOf(record.id))) },
            listOf(record.id))
        val topics = TopicRepository(db)
        val store = TopicEmbeddingRepository(db)
        return memories.associate { memory ->
            val id = topics.create(memory.id, TopicCentroid.of(vector))
            id to store.save(TopicEmbedding(PendingTopic(id, "1").embeddingKey(space), vector))
        }
    }

    private fun change(
        key: String,
        expected: Map<Int, Long> = emptyMap(),
        incoming: Set<Int>,
        parts: List<Set<Int>>,
        embeddings: Map<Int, TopicEmbedding>,
        retain: Int? = null,
    ): GroupChange = GroupChange(key, expected, incoming, parts.mapIndexed { index, ids ->
        ResultingGroup(if (index == 0 && retain != null) GroupTarget.Existing(retain) else GroupTarget.Created("$index"),
            ids, GroupGeometry.centroid(ids.sorted().map { embeddings.getValue(it).vector }))
    }, embeddings.filterKeys { it in parts.flatten() })

    private fun service(db: Database, maxTopics: Int): OrganizePendingTopicsService {
        val repository = GroupRepository(db)
        val generator = object : TopicEmbeddingGenerator {
            override val space = this@GroupRepositoryTest.space
            override fun generate(topic: PendingTopic) = vector
        }
        val settings = GroupPolicySettings(maxTopics = maxTopics)
        return OrganizePendingTopicsService(repository, OrganizeTopic(repository, repository,
            PrepareTopicEmbedding(generator, TopicEmbeddingRepository(db)),
            GroupOrganizationPlanner(GroupPlacementPolicy(settings), GroupSplitPolicy(settings))))
    }

    private fun withDatabase(block: (Database, String) -> Unit) {
        val path = Files.createTempFile("group-store", ".db")
        try { block(DatabaseFactory.init(path.toString()), path.toString()) }
        finally { Files.deleteIfExists(path) }
    }
}
