package com.homeassistant.adapter.outbound.persistence.repo.group

import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeComponents
import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.adapter.outbound.persistence.repo.component.ComponentRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.inbound.memoryextraction.ComponentOrganizationOutcome
import com.homeassistant.application.port.inbound.memoryextraction.GroupMergeOutcome
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbedding
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChange
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRebuildRequired
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupTarget
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChangeConflict
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
        val episodes = EpisodeRepositoryImpl(db)
        val source = SourceDescriptor("test", "private")
        val user = UserId("owner")
        val record = sources.saveAll(source, listOf(SourceRecordDraft("test", "original")), EpisodeAccess.restricted(listOf(user)))
            .recordsToAnalyze.single()
        val committed = episodes.save(EpisodeBatch(user, (1..5).map { EpisodeProposal("title", "fact $it", listOf(record.id)) },
            listOf(record.id)))
        val components = ComponentRepositoryImpl(db)
        val ids = committed.map { components.save(EpisodeComponents(it.id, listOf("knowledge of ${it.content}"))).single().id }.toSet()
        val beforeComponents = components.findByIds(ids)
        val beforeEpisodes = episodes.findVisibleTo(user)
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
        val snapshot = GroupRepositoryImpl(restarted).findSnapshot()
        assertEquals(ids, snapshot.groups.flatMap { it.group.componentIds }.toSet())
        assertEquals(ids.size, snapshot.groups.sumOf { it.group.componentIds.size })
        assertTrue(snapshot.groups.all { it.group.componentIds.size <= 2 })
        assertEquals(beforeComponents, components.findByIds(ids))
        assertEquals(beforeEpisodes, episodes.findVisibleTo(user))
        assertEquals(beforeSources, sources.findBySource(source))
    }

    @Test fun `receipt returns original ids before revision validation and rejects changed payload`() = withDatabase { db, _ ->
        val embeddings = seed(db, 2)
        val repository = GroupRepositoryImpl(db)
        val create = change("create", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings)
        val id = repository.save(create).single().id
        assertEquals(listOf(id), repository.save(create).map { it.id })
        repository.save(change("attach", mapOf(id to 1), setOf(2), listOf(setOf(1, 2)), embeddings, id))
        assertEquals(listOf(id), repository.save(create).map { it.id })
        assertFailsWith<IllegalArgumentException> { repository.save(create.copy(incomingComponentIds = setOf(2))) }
        assertEquals(setOf(1, 2), repository.findSnapshot().groups.single().group.componentIds)
    }

    @Test fun `stale revision and already assigned incoming reject without reserving operation keys`() = withDatabase { db, _ ->
        val embeddings = seed(db, 2)
        val repository = GroupRepositoryImpl(db)
        val id = repository.save(change("a", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings)).single().id
        val stale = change("b", mapOf(id to 9), setOf(2), listOf(setOf(1, 2)), embeddings, id)
        assertFailsWith<GroupChangeConflict> { repository.save(stale) }
        assertFailsWith<GroupChangeConflict> { repository.save(change("c", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings)) }
        assertEquals(1L, transaction(db) { GroupReceiptTable.selectAll().count() })
        repository.save(stale.copy(expectedGroups = mapOf(id to 1)))
    }

    @Test fun `merge storage contract replaces multiple groups atomically and preserves retained id`() = withDatabase { db, _ ->
        val embeddings = seed(db, 2)
        val repository = GroupRepositoryImpl(db)
        val first = repository.save(change("a", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings)).single().id
        val second = repository.save(change("b", incoming = setOf(2), parts = listOf(setOf(2)), embeddings = embeddings)).single().id
        val merge = change("merge", mapOf(first to 1, second to 1), emptySet(), listOf(setOf(1, 2)), embeddings, first)
        assertEquals(listOf(first), repository.save(merge).map { it.id })
        val group = repository.findSnapshot().groups.single()
        assertEquals(Group(first, setOf(1, 2)), group.group)
        assertEquals(2L, group.revision)
        assertEquals(listOf(first), repository.save(merge).map { it.id })
    }

    @Test fun `a single-component group merges into its neighbor and is deleted`() = withDatabase { db, _ ->
        val embeddings = seed(db, 3)
        val repository = GroupRepositoryImpl(db)
        val pair = repository.save(change("a", incoming = setOf(1, 2), parts = listOf(setOf(1, 2)), embeddings = embeddings)).single().id
        val single = repository.save(change("b", incoming = setOf(3), parts = listOf(setOf(3)), embeddings = embeddings)).single().id

        val outcomes = MergeSmallGroups(repository, GroupMergePolicy(GroupPolicySettings())).execute()

        assertEquals(listOf(GroupMergeOutcome.Merged(pair, single)), outcomes)
        assertEquals(listOf(Group(pair, setOf(1, 2, 3))), repository.findSnapshot().groups.map { it.group })
    }

    @Test fun `missing stolen omitted components and incorrect centroid roll back receipt and groups`() = withDatabase { db, _ ->
        val embeddings = seed(db, 3)
        val repository = GroupRepositoryImpl(db)
        val id = repository.save(change("a", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings)).single().id
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
        invalid.forEach { assertFailsWith<IllegalArgumentException> { repository.save(it) } }
        assertEquals(setOf(1), repository.findSnapshot().groups.single().group.componentIds)
        assertEquals(1L, transaction(db) { GroupReceiptTable.selectAll().count() })
    }

    @Test fun `failure during membership replacement rolls back revision centroid and receipt`() = withDatabase { db, _ ->
        val embeddings = seed(db, 2)
        val repository = GroupRepositoryImpl(db)
        val id = repository.save(change("a", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings)).single().id
        val before = repository.findSnapshot()
        transaction(db) { exec("CREATE TRIGGER fail_group BEFORE INSERT ON component_group_memberships BEGIN SELECT RAISE(ABORT, 'forced'); END") }
        val attach = change("b", mapOf(id to 1), setOf(2), listOf(setOf(1, 2)), embeddings, id)
        assertFails { repository.save(attach) }
        assertEquals(before, repository.findSnapshot())
        assertEquals(1L, transaction(db) { GroupReceiptTable.selectAll().count() })
        transaction(db) { exec("DROP TRIGGER fail_group") }
        repository.save(attach)
    }

    @Test fun `visible groups hold only the members the user can read and leave out groups without one`() = withDatabase { db, _ ->
        val embeddings = seed(db, 1, EpisodeAccess.PUBLIC, "public") +
            seed(db, 1, EpisodeAccess.restricted(listOf(UserId("a"))), "a") +
            seed(db, 1, EpisodeAccess.restricted(listOf(UserId("b"))), "b")
        val repository = GroupRepositoryImpl(db)
        repository.save(change("shared", incoming = setOf(1, 2), parts = listOf(setOf(1, 2)), embeddings = embeddings))
        repository.save(change("private", incoming = setOf(3), parts = listOf(setOf(3)), embeddings = embeddings))
        val stored = repository.findSnapshot().groups
        val components = ComponentRepositoryImpl(db).findByIds(embeddings.keys).associateBy { it.id }

        listOf("a", "b", "c").map(::UserId).forEach { user ->
            val expected = stored.mapNotNull { group ->
                val visible = group.group.componentIds.filter { components.getValue(it).isVisibleTo(user) }.toSet()
                if (visible.isEmpty()) null else group.copy(group = Group(group.group.id, visible))
            }
            assertEquals(expected, repository.findVisibleTo(user))
        }
    }

    @Test fun `concurrent assignment of one component has one winner and one conflict`() = withDatabase { db, _ ->
        val embeddings = seed(db, 1)
        val repository = GroupRepositoryImpl(db)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { attempt -> pool.submit<Result<List<Group>>> {
                start.await()
                runCatching { repository.save(change("race-$attempt", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings)) }
            } }
            start.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is GroupChangeConflict })
            assertEquals(1, repository.findSnapshot().groups.size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `embedding cache survives restart and group model mixing requires rebuild`() = withDatabase { db, path ->
        val embeddings = seed(db, 2)
        val repository = GroupRepositoryImpl(db)
        repository.save(change("a", incoming = setOf(1), parts = listOf(setOf(1)), embeddings = embeddings))
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
            repository.save(change("mixed", incoming = setOf(2), parts = listOf(setOf(2)), embeddings = mapOf(2 to replacement)))
        }
        assertEquals(1, repository.findSnapshot().groups.size)
    }

    private fun seed(
        db: Database,
        count: Int,
        access: EpisodeAccess = EpisodeAccess.restricted(listOf(UserId("owner"))),
        key: String = "seed",
    ): Map<Int, ComponentEmbedding> {
        val user = UserId("owner")
        val record = SourceRecordRepositoryImpl(db).saveAll(SourceDescriptor("test", key), listOf(SourceRecordDraft(key, key)),
            access).recordsToAnalyze.single()
        val episodes = EpisodeRepositoryImpl(db).save(EpisodeBatch(user, (1..count).map { EpisodeProposal("title", "fact $it", listOf(record.id)) },
            listOf(record.id)))
        val components = ComponentRepositoryImpl(db)
        val store = ComponentEmbeddingRepository(db)
        return episodes.associate { episode ->
            val statement = "knowledge of ${episode.content}"
            val id = components.save(EpisodeComponents(episode.id, listOf(statement))).single().id
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
        val repository = GroupRepositoryImpl(db)
        val generator = object : EmbeddingGenerator {
            override val space = this@GroupRepositoryTest.space
            override fun isAvailable() = true
            override fun embedDocument(text: String) = vector
            override fun embedQuery(text: String) = vector
        }
        val settings = GroupPolicySettings(maxComponents = maxComponents)
        val organizer = OrganizeComponentIntoGroups(
            repository,
            PrepareComponentEmbedding(ComponentRepositoryImpl(db), generator, ComponentEmbeddingRepository(db)),
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
