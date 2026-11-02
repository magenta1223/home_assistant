package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable
import com.homeassistant.adapter.outbound.persistence.repo.group.GroupRepository
import com.homeassistant.adapter.outbound.persistence.repo.group.ComponentEmbeddingRepository
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.input.component.EpisodeAssignmentOutcome
import com.homeassistant.application.port.output.group.*
import com.homeassistant.application.port.output.episode.write.IdempotentEpisodeProposal
import com.homeassistant.application.port.output.component.*
import com.homeassistant.application.usecase.group.*
import com.homeassistant.application.usecase.component.*
import com.homeassistant.domain.group.*
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.component.ComponentCentroid
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.*

class EpisodeComponentAssignmentTest {
    private val space = EmbeddingSpace("test", "passage:v1")
    private val east = ComponentVector.normalized(listOf(1.0, 0.0))
    private val north = ComponentVector.normalized(listOf(0.0, 1.0))
    private val nearEast = ComponentVector.normalized(listOf(1.0, 0.2))
    private val user = UserId("owner")

    @Test fun `near episodes share a component, a far one starts another and groups follow`() = withDatabase { db, path ->
        val (boiler, rent) = seedEpisodes(db, "first", "보일러 점검 예약", "월세 이체일은 25일")
        val vectors = mutableMapOf("보일러 점검 예약" to east, "월세 이체일은 25일" to north)
        val first = service(db, vectors).execute(10)
        assertEquals(listOf(true, true), first.episodes.map { (it.outcome as EpisodeAssignmentOutcome.Assigned).created })
        val components = ComponentRepository(db)
        val (boilerComponent, housingComponent) = components.centroids().keys.toList()
        assertEquals(setOf(boilerComponent, housingComponent), GroupRepository(db).snapshot().groups.flatMap { it.group.componentIds }.toSet())

        val (repair) = seedEpisodes(db, "second", "보일러 수리비 12만 원")
        vectors["보일러 수리비 12만 원"] = nearEast
        val result = service(db, vectors).execute(10)

        assertEquals(EpisodeAssignmentOutcome.Assigned(listOf(boilerComponent), created = false), result.episodes.single().outcome)
        val stored = components.read(listOf(boilerComponent, housingComponent)).associateBy { it.id }
        val access = EpisodeAccess.restricted(listOf(user))
        assertEquals(mapOf(access to listOf(boiler, repair)), stored.getValue(boilerComponent).slices)
        assertEquals(mapOf(access to listOf(rent)), stored.getValue(housingComponent).slices)
        assertEquals(ComponentCentroid.of(east) + nearEast, stored.getValue(boilerComponent).centroid)
        assertTrue(result.groupRefreshes.isNotEmpty())
        assertTrue(GroupRepository(db).snapshot().componentsWithOutdatedGroupVector.isEmpty())

        val restarted = DatabaseFactory.init(path)
        assertTrue(service(restarted, vectors).execute(10).episodes.isEmpty())
    }

    @Test fun `a failed write records the attempt and the retry creates the component once`() = withDatabase { db, _ ->
        val (episode) = seedEpisodes(db, "first", "화분 물주기")
        val vectors = mapOf("화분 물주기" to east)
        val failing = service(db, vectors, store = object : ComponentMaintenanceStore by ComponentRepository(db) {
            override fun create(episodeId: Int, centroid: ComponentCentroid): Int = error("disk full")
        })
        assertIs<EpisodeAssignmentOutcome.Failed>(failing.execute(10).episodes.single().outcome)
        assertEquals(OutboxRow("FAILED", 1, "disk full"), outboxRow(db, episode))

        service(db, vectors).execute(10)
        assertEquals(1, ComponentRepository(db).centroids().size)
        assertEquals(OutboxRow("COMPLETED", 2, null), outboxRow(db, episode))
    }

    private fun seedEpisodes(db: Database, key: String, vararg contents: String): List<Int> {
        val record = SourceRecordRepositoryImpl(db).saveAll(SourceDescriptor("test", key), listOf(SourceRecordDraft(key, contents.joinToString())),
            EpisodeAccess.restricted(listOf(user))).recordsToAnalyze.single()
        return EpisodeRepository(db).commit(user, contents.mapIndexed { index, content ->
            IdempotentEpisodeProposal("$key-$index", EpisodeProposal("title", content, listOf(record.id)))
        }, listOf(record.id)).map { it.id }
    }

    private fun service(
        db: Database,
        vectors: Map<String, ComponentVector>,
        store: ComponentMaintenanceStore = ComponentRepository(db),
    ): OrganizePendingEpisodesService {
        val groups = GroupRepository(db)
        val episodeEmbeddings = PrepareEpisodeEmbedding(object : EpisodeEmbeddingGenerator {
            override val space = this@EpisodeComponentAssignmentTest.space
            override fun isAvailable() = true
            override fun generate(content: String) = vectors.getValue(content)
        }, EpisodeEmbeddingRepository(db))
        val componentEmbeddings = PrepareComponentEmbedding(ComponentCentroidVectors(ComponentRepository(db), space), ComponentEmbeddingRepository(db))
        val settings = GroupPolicySettings()
        return OrganizePendingEpisodesService(
            ComponentAssignmentOutboxRepository(db),
            AssignEpisodeToComponents(store, episodeEmbeddings),
            RefreshGroupedComponentEmbeddings(groups, groups, componentEmbeddings),
            OrganizePendingComponentsService(groups, OrganizeComponent(groups, groups, componentEmbeddings,
                GroupOrganizationPlanner(GroupPlacementPolicy(settings), GroupSplitPolicy(settings)))),
            MergeSmallGroups(groups, groups, GroupMergePolicy(settings)),
            retryDelayMillis = 0,
        )
    }

    private data class OutboxRow(val status: String, val attempts: Int, val lastError: String?)

    private fun outboxRow(db: Database, episodeId: Int): OutboxRow? = transaction(db) {
        ComponentAssignmentOutboxTable.selectAll().where { ComponentAssignmentOutboxTable.episodeId eq episodeId }.singleOrNull()?.let {
            OutboxRow(it[ComponentAssignmentOutboxTable.status], it[ComponentAssignmentOutboxTable.attempts], it[ComponentAssignmentOutboxTable.lastError])
        }
    }

    private fun withDatabase(block: (Database, String) -> Unit) {
        val path = Files.createTempFile("episode-component-assignment", ".db")
        try { block(DatabaseFactory.init(path.toString()), path.toString()) }
        finally { Files.deleteIfExists(path) }
    }
}
