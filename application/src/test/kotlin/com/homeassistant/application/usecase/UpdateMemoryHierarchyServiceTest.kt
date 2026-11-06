package com.homeassistant.application.usecase

import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentRepository
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeComponents
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRepository
import com.homeassistant.domain.identity.UserId
import com.homeassistant.application.port.inbound.memoryextraction.ComponentPlacementOutcome
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAssignmentOutcome
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAssignmentResult
import com.homeassistant.application.port.inbound.memoryextraction.MemoryHierarchyUpdate
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentAssignmentOutbox
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentAssignmentTask
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentKnowledgeExtractor
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbedding
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingStore
import com.homeassistant.application.port.outbound.memoryextraction.component.PendingEpisode
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbedding
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingStore
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChange
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupSnapshot
import com.homeassistant.application.port.outbound.memoryextraction.group.PendingComponent
import com.homeassistant.application.port.outbound.memoryextraction.group.PendingComponentSource
import com.homeassistant.application.port.outbound.memoryextraction.group.StoredGroup
import com.homeassistant.application.usecase.memoryextraction.UpdateMemoryHierarchyService
import com.homeassistant.application.usecase.memoryextraction.component.AssignEpisodeToComponents
import com.homeassistant.application.usecase.memoryextraction.component.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.memoryextraction.group.MergeSmallGroups
import com.homeassistant.application.usecase.memoryextraction.group.OrganizeComponentIntoGroups
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.group.Group
import com.homeassistant.domain.group.GroupMergePolicy
import com.homeassistant.domain.group.GroupOrganizationPlanner
import com.homeassistant.domain.group.GroupPlacementPolicy
import com.homeassistant.domain.group.GroupPolicySettings
import com.homeassistant.domain.group.GroupSplitPolicy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateMemoryHierarchyServiceTest {
    @Test
    fun `nothing is read or written without the assignment services`() {
        val fixture = Fixture(embeddingReady = false)
        assertEquals(MemoryHierarchyUpdate.Skipped, fixture.service.execute(20))
        assertEquals(emptyList(), fixture.outboxCalls)
    }

    @Test
    fun `claims use the batch size, retry delay, processing lease and attempt limit after queuing unassigned episodes`() {
        val fixture = Fixture()
        fixture.service.execute(7)
        assertEquals(listOf("enqueueUnassigned", "claim:7:${NOW - 60_000L}:${NOW - 600_000L}:5"), fixture.outboxCalls.take(2))
    }

    @Test
    fun `a failed episode is recorded as failed and the next episode is still assigned`() {
        val fixture = Fixture(claimable = 2, failingEpisodes = setOf(1))
        fixture.statements = mapOf(2 to listOf("fact"))
        val update = assertIs<MemoryHierarchyUpdate.Completed>(fixture.service.execute(20))
        assertEquals(
            listOf(
                EpisodeAssignmentResult(1, EpisodeAssignmentOutcome.Failed("model unavailable")),
                EpisodeAssignmentResult(2, EpisodeAssignmentOutcome.Assigned(listOf(1))),
            ),
            update.episodes,
        )
        assertEquals(listOf("failed:1:1", "completed:2:1"), fixture.outboxCalls.drop(2))
    }

    @Test
    fun `every unplaced component is placed and a failure fails that component only`() {
        val fixture = Fixture(claimable = 0, unplaced = 3, failingComponents = setOf(2))
        val update = assertIs<MemoryHierarchyUpdate.Completed>(fixture.service.execute(20))
        assertEquals(listOf(1, 2, 3), update.components.map { it.componentId })
        assertIs<ComponentPlacementOutcome.Failed>(update.components[1].outcome)
        assertEquals(listOf(setOf(1), setOf(3)), fixture.groupWrites.map { it.incomingComponentIds })
    }

    @Test
    fun `outdated group vectors stop placement without merging`() {
        val fixture = Fixture(claimable = 0, unplaced = 2)
        fixture.snapshot = GroupSnapshot(
            listOf(StoredGroup(Group(4, setOf(9)), 1, VECTOR)),
            mapOf(9 to ComponentEmbedding(PendingComponent(9, "9").embeddingKey(EmbeddingSpace("old", "v1")), VECTOR)),
        )
        val update = assertIs<MemoryHierarchyUpdate.RebuildRequired>(fixture.service.execute(20))
        assertTrue(update.components.isEmpty())
        assertTrue(fixture.groupWrites.isEmpty())
    }

    private class Fixture(
        private val embeddingReady: Boolean = true,
        private val claimable: Int = 1,
        private val failingEpisodes: Set<Int> = emptySet(),
        private val unplaced: Int = 0,
        private val failingComponents: Set<Int> = emptySet(),
    ) {
        val outboxCalls = mutableListOf<String>()
        val groupWrites = mutableListOf<GroupChange>()
        var statements = emptyMap<Int, List<String>>()
        var snapshot = GroupSnapshot(emptyList(), emptyMap())
        private val components = mutableListOf<Component>()

        private val outbox = object : ComponentAssignmentOutbox {
            override fun claimReady(
                limit: Int,
                now: Long,
                retryBefore: Long,
                staleProcessingBefore: Long,
                maxAttempts: Int,
            ): List<ComponentAssignmentTask> {
                outboxCalls += "claim:$limit:$retryBefore:$staleProcessingBefore:$maxAttempts"
                return (1..claimable).map { ComponentAssignmentTask(it, 1, episode(it)) }
            }

            override fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long): Boolean =
                outboxCalls.add("completed:$outboxId:$expectedAttempt")

            override fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long): Boolean =
                outboxCalls.add("failed:$outboxId:$expectedAttempt")

            override fun enqueueUnassigned(now: Long): Int {
                outboxCalls += "enqueueUnassigned"
                return 0
            }
        }

        private val texts = object : EmbeddingGenerator {
            override val space = SPACE
            override fun isAvailable() = embeddingReady
            override fun embedDocument(text: String): ComponentVector {
                if (text in failingComponents.map(::placementStatement)) error("embedding unavailable")
                return VECTOR
            }
            override fun embedQuery(text: String) = VECTOR
        }
        private val componentRepository = object : ComponentRepository {
            override fun findByIds(ids: Collection<Int>) = components.filter { it.id in ids }
            override fun findCovering(access: EpisodeAccess) = components.filter { it.access.covers(access) }
            override fun findVisibleTo(userId: UserId) = components.filter { it.isVisibleTo(userId) }
            override fun save(extraction: EpisodeComponents) = extraction.statements.map {
                Component(components.size + 1, it, extraction.episodeId, EpisodeAccess.PUBLIC).also(components::add)
            }
        }
        private val assignEpisode = AssignEpisodeToComponents(
            componentRepository,
            PrepareEpisodeEmbedding(texts, EpisodeCache()),
            PrepareComponentEmbedding(componentRepository, texts, ComponentCache()),
            ComponentKnowledgeExtractor { episode, _ ->
                if (episode.id in failingEpisodes) error("model unavailable")
                statements[episode.id].orEmpty()
            },
        )

        private val unplacedComponents = object : ComponentRepository {
            override fun findByIds(ids: Collection<Int>) = ids.map(::unplacedComponent)
            override fun findCovering(access: EpisodeAccess) = (1..unplaced).map(::unplacedComponent)
            override fun findVisibleTo(userId: UserId) = (1..unplaced).map(::unplacedComponent)
            override fun save(extraction: EpisodeComponents) = error("organization never saves Components")
        }
        private val groups = object : GroupRepository {
            override fun findVisibleTo(userId: UserId) = error("organization never searches Groups")
            override fun findSnapshot() = snapshot
            override fun save(change: GroupChange): List<Group> {
                groupWrites += change
                return change.resultingGroups.map { Group(groupWrites.size, it.componentIds) }
            }
        }
        private val settings = GroupPolicySettings()

        val service = UpdateMemoryHierarchyService(
            outbox = outbox,
            pendingComponents = object : PendingComponentSource {
                override fun pending(limit: Int) = (1..unplaced).map { PendingComponent(it, "$it") }
            },
            assignEpisode = assignEpisode,
            organizeComponent = OrganizeComponentIntoGroups(
                groups,
                PrepareComponentEmbedding(unplacedComponents, texts, ComponentCache()),
                GroupOrganizationPlanner(GroupPlacementPolicy(settings), GroupSplitPolicy(settings)),
            ),
            mergeSmallGroups = MergeSmallGroups(groups, GroupMergePolicy(settings)),
            clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
        )

        private fun unplacedComponent(id: Int) = Component(id, placementStatement(id), id, EpisodeAccess.PUBLIC)
        private fun placementStatement(id: Int) = "placement $id"
        private fun episode(id: Int) = PendingEpisode(id, "title $id", "$id", EpisodeAccess.PUBLIC, listOf("message $id"))
    }

    private class EpisodeCache : EpisodeEmbeddingStore {
        private val cache = mutableMapOf<EpisodeEmbeddingKey, EpisodeEmbedding>()
        override fun find(key: EpisodeEmbeddingKey) = cache[key]
        override fun save(embedding: EpisodeEmbedding) = cache.getOrPut(embedding.key) { embedding }
    }

    private class ComponentCache : ComponentEmbeddingStore {
        private val cache = mutableMapOf<ComponentEmbeddingKey, ComponentEmbedding>()
        override fun find(key: ComponentEmbeddingKey) = cache[key]
        override fun save(embedding: ComponentEmbedding) = cache.getOrPut(embedding.key) { embedding }
    }

    private companion object {
        const val NOW = 1_000_000L
        val SPACE = EmbeddingSpace("test", "v1")
        val VECTOR = ComponentVector.normalized(listOf(1.0, 0.0))
    }
}
