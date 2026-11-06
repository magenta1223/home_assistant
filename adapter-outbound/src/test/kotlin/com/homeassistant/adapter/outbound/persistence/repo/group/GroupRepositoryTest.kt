package com.homeassistant.adapter.outbound.persistence.repo.group

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.adapter.outbound.persistence.repo.component.ComponentRepository
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.inbound.memoryextraction.ComponentOrganizationOutcome
import com.homeassistant.application.port.inbound.memoryextraction.GroupMergeOutcome
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbedding
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingGenerator
import com.homeassistant.application.port.outbound.memoryextraction.group.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChange
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRebuildRequired
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupTarget
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupWriteResult
import com.homeassistant.application.port.outbound.memoryextraction.group.PendingComponent
import com.homeassistant.application.port.outbound.memoryextraction.group.ResultingGroup
import com.homeassistant.application.port.outbound.memoryextraction.group.descriptionFingerprint
import com.homeassistant.application.usecase.memoryextraction.group.MergeSmallGroups
import com.homeassistant.application.usecase.memoryextraction.group.OrganizeComponentIntoGroups
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
import com.homeassistant.domain.group.*
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeProposal
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
    private val vector = ComponentVector.normalized(listOf(1.0, 0.0))

    @Test
    fun `create attach split restart and repeat preserve all component state`() = withDatabase { db, path ->
        val sources = SourceRecordRepositoryImpl(db)
        val episodes = EpisodeRepository(db)
        val source = SourceDescriptor("test", "private")
        val user = UserId("owner")
        val record = sources.saveAll(source, listOf(SourceRecordDraft("test", "original")), EpisodeAccess.restricted(listOf(user)))
            .recordsToAnalyze.single()
        val committed = episodes.commit(user, (1..5).map { EpisodeProposal("title", "fact $it", listOf(record.id)) },
            listOf(record.id))
        val components = ComponentRepository(db)
        val ids = committed.map { components.writeComponents(it.id, listOf("knowledge of ${it.content}")).single() }.toSet()
        val beforeComponents = components.read(ids)
        val beforeEpisodes = episodes.getEpisodes(user)
        val beforeSources = sources.findBySource(source)
        val first = organizePending(db, 2, limit = 3)
        assertEquals(3, first.size)
        assertTrue(first.all { it is ComponentOrganizationOutcome.Organized })
        assertTrue(first.any { (it as ComponentOrganizationOutcome.Organized).split is GroupSplit.Divided })
        val restarted = DatabaseFactory.init(path)
        val remaining = organizePending(restarted, 2, limit = 10)
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it is ComponentOrganizationOutcome.Organized })
        assertTrue(organizePending(restarted, 2, limit = 10).isEmpty())
        val snapshot = GroupRepository(restarted).snapshot()
        assertEquals(ids, snapshot.groups.flatMap { it.group.componentIds }.toSet())
        assertEquals(ids.size, snapshot.groups.sumOf { it.group.componentIds.size })
        assertTrue(snapshot.groups.all { it.group.componentIds.size <= 2 })
        assertEquals(snapshot.groups, GroupRepository(restarted).groups())
        assertEquals(beforeComponents, components.read(ids))
        assertEquals(beforeEpisodes, episodes.getEpisodes(user))
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
        assertFailsWith<IllegalArgumentException> { repository.apply(create.copy(incomingComponentIds = setOf(2))) }
        assertEquals(setOf(1, 2), repository.snapshot().groups.single().group.componentIds)
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

    @Test fun `a single-component group merges into its neighbor and is deleted`() = withDatabase { db, _ ->
        val embeddings = seed(db, 3)
        val repository = GroupRepository(db)
        val pair = assertIs<GroupWriteResult.Applied>(repository.apply(change("a", incoming = setOf(1, 2), parts = listOf(setOf(1, 2)), embeddings = embeddings))).groupIds.single()
        val single = assertIs<GroupWriteResult.Applied>(repository.apply(change("b", incoming = setOf(3), parts = listOf(setOf(3)), embeddings = embeddings))).groupIds.single()

        val outcomes = MergeSmallGroups(repository, repository, GroupMergePolicy(GroupPolicySettings())).execute()

        assertEquals(listOf(GroupMergeOutcome.Merged(pair, single)), outcomes)
        assertEquals(listOf(Group(pair, setOf(1, 2, 3))), repository.snapshot().groups.map { it.group })
    }

    @Test fun `missing stolen omitted components and incorrect centroid roll back receipt and groups`() = withDatabase { db, _ ->
        val embeddings = seed(db, 3)
        val repository = GroupRepository(db)
        val id = assertIs<GroupWriteResult.Applied>(repository.apply(change("a", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings))).groupIds.single()
        val invalid = listOf(
            change("omit", mapOf(id to 1), setOf(2), listOf(setOf(2)), embeddings, id),
            change("steal", incoming = setOf(2), parts = listOf(setOf(1, 2)), embeddings = embeddings),
            change("extra", incoming = setOf(2), parts = listOf(setOf(2, 3)), embeddings = embeddings),
            change("wrong-centroid", incoming = setOf(2), parts = listOf(setOf(2)), embeddings = embeddings).let {
                it.copy(resultingGroups = it.resultingGroups.map { result -> result.copy(centroid = ComponentVector.normalized(listOf(0.0, 1.0))) })
            },
            change("missing", incoming = setOf(999), parts = listOf(setOf(999)), embeddings =
                mapOf(999 to ComponentEmbedding(PendingComponent(999, "1").embeddingKey(space), vector))),
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { repository.apply(it) } }
        assertEquals(setOf(1), repository.snapshot().groups.single().group.componentIds)
        assertEquals(1L, transaction(db) { GroupReceiptTable.selectAll().count() })
    }

    @Test fun `failure during membership replacement rolls back revision centroid and receipt`() = withDatabase { db, _ ->
        val embeddings = seed(db, 2)
        val repository = GroupRepository(db)
        val id = assertIs<GroupWriteResult.Applied>(repository.apply(change("a", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings))).groupIds.single()
        val before = repository.snapshot()
        transaction(db) { exec("CREATE TRIGGER fail_group BEFORE INSERT ON component_group_memberships BEGIN SELECT RAISE(ABORT, 'forced'); END") }
        val attach = change("b", mapOf(id to 1), setOf(2), listOf(setOf(1, 2)), embeddings, id)
        assertFails { repository.apply(attach) }
        assertEquals(before, repository.snapshot())
        assertEquals(1L, transaction(db) { GroupReceiptTable.selectAll().count() })
        transaction(db) { exec("DROP TRIGGER fail_group") }
        assertIs<GroupWriteResult.Applied>(repository.apply(attach))
    }

    @Test fun `concurrent assignment of one component has one winner and one conflict`() = withDatabase { db, _ ->
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
        val restarted = ComponentEmbeddingRepository(DatabaseFactory.init(path))
        assertEquals(embeddings.getValue(1), restarted.find(embeddings.getValue(1).key))
        // The first vector for a cache key wins even if another embedding request returns different coordinates.
        assertEquals(embeddings.getValue(1), restarted.save(embeddings.getValue(1).copy(vector = ComponentVector.normalized(listOf(0.0, 1.0)))))
        val replacement = restarted.save(embeddings.getValue(2).let { it.copy(key = it.key.copy(space = EmbeddingSpace(
            "new",
            "v1"
        )
        )) })
        assertFailsWith<GroupRebuildRequired> {
            repository.apply(change("mixed", incoming = setOf(2), parts = listOf(setOf(2)), embeddings = mapOf(2 to replacement)))
        }
        assertEquals(1, repository.snapshot().groups.size)
    }

    private fun seed(db: Database, count: Int): Map<Int, ComponentEmbedding> {
        val user = UserId("owner")
        val record = SourceRecordRepositoryImpl(db).saveAll(SourceDescriptor("test", "seed"), listOf(SourceRecordDraft("seed", "seed")),
            EpisodeAccess.restricted(listOf(user))).recordsToAnalyze.single()
        val episodes = EpisodeRepository(db).commit(user, (1..count).map { EpisodeProposal("title", "fact $it", listOf(record.id)) },
            listOf(record.id))
        val components = ComponentRepository(db)
        val store = ComponentEmbeddingRepository(db)
        return episodes.associate { episode ->
            val statement = "knowledge of ${episode.content}"
            val id = components.writeComponents(episode.id, listOf(statement)).single()
            id to store.save(
                ComponentEmbedding(
                    PendingComponent(id, descriptionFingerprint(statement)).embeddingKey(
                        space
                    ), vector
                )
            )
        }
    }

    private fun change(
        key: String,
        expected: Map<Int, Long> = emptyMap(),
        incoming: Set<Int>,
        parts: List<Set<Int>>,
        embeddings: Map<Int, ComponentEmbedding>,
        retain: Int? = null,
    ): GroupChange = GroupChange(key, expected, incoming, parts.mapIndexed { index, ids ->
        ResultingGroup(
            if (index == 0 && retain != null) GroupTarget.Existing(retain) else GroupTarget.Created("$index"),
            ids, GroupGeometry.centroid(ids.sorted().map { embeddings.getValue(it).vector })
        )
    }, embeddings.filterKeys { it in parts.flatten() })

    /** Places up to [limit] unplaced Components one by one, as the assignment worker does. */
    private fun organizePending(db: Database, maxComponents: Int, limit: Int): List<ComponentOrganizationOutcome> {
        val repository = GroupRepository(db)
        val generator = object : ComponentEmbeddingGenerator {
            override val space = this@GroupRepositoryTest.space
            override fun generate(component: PendingComponent) = vector
        }
        val settings = GroupPolicySettings(maxComponents = maxComponents)
        val organizer = OrganizeComponentIntoGroups(
            repository, repository,
            PrepareComponentEmbedding(generator, ComponentEmbeddingRepository(db)),
            GroupOrganizationPlanner(GroupPlacementPolicy(settings), GroupSplitPolicy(settings))
        )
        return repository.pending(limit).map(organizer::organize)
    }

    private fun withDatabase(block: (Database, String) -> Unit) {
        val path = Files.createTempFile("group-store", ".db")
        try { block(DatabaseFactory.init(path.toString()), path.toString()) }
        finally { Files.deleteIfExists(path) }
    }
}
