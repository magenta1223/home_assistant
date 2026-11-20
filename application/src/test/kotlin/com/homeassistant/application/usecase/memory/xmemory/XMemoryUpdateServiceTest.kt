package com.homeassistant.application.usecase.memory.xmemory

import com.homeassistant.application.port.outbound.memory.xmemory.component.ComponentRepository
import com.homeassistant.application.port.outbound.memory.xmemory.component.EpisodeComponents
import com.homeassistant.application.port.outbound.memory.xmemory.group.GroupRepository
import com.homeassistant.domain.identity.UserId
import com.homeassistant.application.port.inbound.memory.SourceMemorizationOutcome
import com.homeassistant.application.port.inbound.memory.SourceMemorizationResult
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeBatch
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeExtractionClaim
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeExtractionQueue
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeExtractor
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeRepository
import com.homeassistant.application.usecase.memory.xmemory.episode.ExtractEpisodes
import com.homeassistant.domain.memory.xmemory.episode.Episode
import com.homeassistant.domain.memory.xmemory.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.application.port.inbound.memory.MemoryUpdateResult
import com.homeassistant.application.port.outbound.memory.xmemory.component.ComponentAssignmentOutbox
import com.homeassistant.application.port.outbound.memory.xmemory.component.ComponentAssignmentTask
import com.homeassistant.application.port.outbound.memory.xmemory.component.ComponentExtractor
import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.application.port.outbound.memory.xmemory.embedding.EpisodeEmbeddingKey
import com.homeassistant.application.port.outbound.memory.xmemory.embedding.EpisodeEmbeddingRepository
import com.homeassistant.application.port.outbound.memory.xmemory.component.PendingEpisode
import com.homeassistant.application.port.outbound.memory.xmemory.embedding.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.memory.xmemory.embedding.ComponentEmbeddingRepository
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.memory.xmemory.group.GroupChange
import com.homeassistant.application.port.outbound.memory.xmemory.group.GroupSnapshot
import com.homeassistant.application.port.outbound.memory.xmemory.group.StoredGroup
import com.homeassistant.application.usecase.memory.xmemory.component.AssignEpisodeToComponents
import com.homeassistant.application.usecase.memory.xmemory.embedding.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.memory.xmemory.group.MergeSmallGroups
import com.homeassistant.application.usecase.memory.xmemory.group.OrganizeComponentIntoGroups
import com.homeassistant.application.usecase.memory.xmemory.embedding.PrepareComponentEmbedding
import com.homeassistant.domain.memory.xmemory.component.Component
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.embedding.EmbeddingVector
import com.homeassistant.domain.memory.xmemory.group.Group
import com.homeassistant.domain.memory.xmemory.group.GroupMergePolicy
import com.homeassistant.domain.memory.xmemory.group.GroupOrganizationPlanner
import com.homeassistant.domain.memory.xmemory.group.GroupPlacementPolicy
import com.homeassistant.domain.memory.xmemory.group.GroupPolicySettings
import com.homeassistant.domain.memory.xmemory.group.GroupSplitPolicy
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class XMemoryUpdateServiceTest {
    @Test
    fun `nothing is read or written without the assignment services`() = runBlocking<Unit> {
        val fixture = Fixture(embeddingReady = false)
        assertEquals(MemoryUpdateResult.Skipped, fixture.service.execute(20))
        assertEquals(emptyList(), fixture.outboxCalls)
        assertEquals(emptyList(), fixture.extractionCalls)
    }

    @Test
    fun `waiting sources are memorized first and their Episodes are assigned in the same update`() = runBlocking<Unit> {
        val fixture = Fixture(claimable = 0, waitingSources = 2)
        val update = assertIs<MemoryUpdateResult.Completed>(fixture.service.execute(20))
        assertEquals(
            listOf(
                SourceMemorizationResult(source(1), SourceMemorizationOutcome.Memorized(1)),
                SourceMemorizationResult(source(2), SourceMemorizationOutcome.Memorized(1)),
            ),
            update.sources,
        )
        assertEquals(listOf("completed:101:1", "completed:102:1"), fixture.outboxOutcomes())
        assertEquals("claim:${NOW - 60_000L}:${NOW - 600_000L}:5", fixture.extractionCalls.first())
    }

    @Test
    fun `a failed source is recorded as failed and the next source is still memorized`() = runBlocking<Unit> {
        val fixture = Fixture(claimable = 0, waitingSources = 2, failingSources = setOf(1))
        val update = assertIs<MemoryUpdateResult.Completed>(fixture.service.execute(20))
        assertEquals(
            listOf(
                SourceMemorizationResult(source(1), SourceMemorizationOutcome.Failed("model unavailable")),
                SourceMemorizationResult(source(2), SourceMemorizationOutcome.Memorized(1)),
            ),
            update.sources,
        )
        assertEquals(listOf("failed:claim-1"), fixture.extractionCalls.filterNot { it.startsWith("claim") })
    }

    @Test
    fun `claims use the batch size, retry delay, processing lease and attempt limit after queuing unassigned episodes`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.service.execute(7)
        assertEquals(listOf("enqueueUnassigned", "claim:7:${NOW - 60_000L}:${NOW - 600_000L}:5"), fixture.outboxCalls.take(2))
    }

    @Test
    fun `a failed episode is recorded as failed and the next episode is still assigned`() = runBlocking<Unit> {
        val fixture = Fixture(claimable = 2, failingEpisodes = setOf(1))
        fixture.statements = mapOf(2 to listOf("fact"))
        assertIs<MemoryUpdateResult.Completed>(fixture.service.execute(20))
        assertEquals(listOf("failed:1:1", "completed:2:1"), fixture.outboxOutcomes())
    }

    @Test
    fun `every waiting episode is assigned in one update, batch after batch`() = runBlocking<Unit> {
        val fixture = Fixture(claimable = 5)
        fixture.service.execute(2)
        assertEquals((1..5).map { "completed:$it:1" }, fixture.outboxOutcomes())
    }

    @Test
    fun `an episode that fails is not retried in the same update even after the retry delay passes`() = runBlocking<Unit> {
        val fixture = Fixture(claimable = 2, failingEpisodes = setOf(1), clock = AdvancingClock(stepMillis = 61_000L))
        fixture.service.execute(1)
        assertEquals(listOf("failed:1:1", "completed:2:1"), fixture.outboxOutcomes())
    }

    @Test
    fun `every unplaced component is placed in one update and a failing one is tried only once`() = runBlocking<Unit> {
        val fixture = Fixture(claimable = 0, unplaced = 5, failingComponents = setOf(1))
        assertIs<MemoryUpdateResult.Completed>(fixture.service.execute(2))
        assertEquals((2..5).map(::setOf), fixture.groupWrites.map { it.incomingComponentIds })
        assertEquals(1, fixture.failedPlacements)
    }

    @Test
    fun `a failing component leaves the others placed`() = runBlocking<Unit> {
        val fixture = Fixture(claimable = 0, unplaced = 3, failingComponents = setOf(2))
        assertIs<MemoryUpdateResult.Completed>(fixture.service.execute(20))
        assertEquals(listOf(setOf(1), setOf(3)), fixture.groupWrites.map { it.incomingComponentIds })
    }

    @Test
    fun `outdated group vectors stop the update without placing or merging`() = runBlocking<Unit> {
        val fixture = Fixture(claimable = 0, unplaced = 2, waitingSources = 1)
        fixture.snapshot = GroupSnapshot(
            listOf(StoredGroup(Group(4, setOf(9)), 1, VECTOR)),
            mapOf(9 to Embedding(ComponentEmbeddingKey(9, "9", EmbeddingSpace("old", "v1")), VECTOR)),
        )
        val update = assertIs<MemoryUpdateResult.Stopped>(fixture.service.execute(20))
        assertEquals(listOf(SourceMemorizationResult(source(1), SourceMemorizationOutcome.Memorized(1))), update.sources)
        assertTrue(fixture.groupWrites.isEmpty())
    }

    private class Fixture(
        private val embeddingReady: Boolean = true,
        private val claimable: Int = 1,
        private val failingEpisodes: Set<Int> = emptySet(),
        private val unplaced: Int = 0,
        private val failingComponents: Set<Int> = emptySet(),
        private val waitingSources: Int = 0,
        private val failingSources: Set<Int> = emptySet(),
        private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
    ) {
        val outboxCalls = mutableListOf<String>()
        var failedPlacements = 0
        val extractionCalls = mutableListOf<String>()
        val groupWrites = mutableListOf<GroupChange>()
        var statements = emptyMap<Int, List<String>>()
        var snapshot = GroupSnapshot(emptyList(), emptyMap())
        private val components = mutableListOf<Component>()
        private val pending = (1..claimable).toMutableList()
        private val failedAt = mutableMapOf<Int, Long>()
        private val sources = (1..waitingSources).toMutableList()

        private val extractionQueue = object : EpisodeExtractionQueue {
            override fun claimNext(now: Long, retryBefore: Long, staleProcessingBefore: Long, maxAttempts: Int): EpisodeExtractionClaim? {
                extractionCalls += "claim:$retryBefore:$staleProcessingBefore:$maxAttempts"
                val id = sources.removeFirstOrNull() ?: return null
                val record = SourceRecord(id, "record $id", "message $id", AccessControl.PUBLIC)
                return EpisodeExtractionClaim("claim-$id", UserId("member"), SourceDocument(source(id), listOf(record)))
            }

            override fun markFailed(claimId: String, error: String, now: Long): Boolean = extractionCalls.add("failed:$claimId")
        }

        /** Stores each extracted Episode as id 100 + its record id and queues it for assignment like the real outbox. */
        private val episodes = object : EpisodeRepository {
            override fun findVisibleTo(userId: UserId) = error("extraction never searches Episodes")
            override fun save(batch: EpisodeBatch) = batch.proposals.map { proposal ->
                val id = 100 + proposal.evidenceIds.single()
                pending += id
                Episode(id, batch.createdBy.value, proposal.title, proposal.content, AccessControl.PUBLIC, proposal.evidenceIds, NOW)
            }
        }

        private val extractEpisodes = ExtractEpisodes(
            object : EpisodeExtractor {
                override suspend fun analyze(document: SourceDocument): List<EpisodeProposal> {
                    val id = document.records.single().id
                    if (id in failingSources) error("model unavailable")
                    return listOf(EpisodeProposal("title $id", "$id", listOf(id)))
                }
            },
            episodes,
        )

        private val outbox = object : ComponentAssignmentOutbox {
            override fun claimReady(
                limit: Int,
                now: Long,
                retryBefore: Long,
                staleProcessingBefore: Long,
                maxAttempts: Int,
            ): List<ComponentAssignmentTask> {
                outboxCalls += "claim:$limit:$retryBefore:$staleProcessingBefore:$maxAttempts"
                val retryable = failedAt.filterValues { it <= retryBefore }.keys
                val ready = (pending + retryable).sorted().take(limit)
                pending.removeAll(ready)
                ready.forEach(failedAt::remove)
                return ready.map { ComponentAssignmentTask(it, 1, episode(it)) }
            }

            override fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long): Boolean =
                outboxCalls.add("completed:$outboxId:$expectedAttempt")

            override fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long): Boolean {
                failedAt[outboxId] = now
                return outboxCalls.add("failed:$outboxId:$expectedAttempt")
            }

            override fun enqueueUnassigned(now: Long): Int {
                outboxCalls += "enqueueUnassigned"
                return 0
            }
        }

        private val texts = object : EmbeddingGenerator {
            override val space = SPACE
            override fun isAvailable() = embeddingReady
            override fun embedDocument(text: String): EmbeddingVector {
                if (text in failingComponents.map(::placementStatement)) {
                    failedPlacements++
                    error("embedding unavailable")
                }
                return VECTOR
            }
            override fun embedQuery(text: String) = VECTOR
        }
        private val componentRepository = object : ComponentRepository {
            override fun findCovering(access: AccessControl) = components.filter { it.access.covers(access) }
            override fun findVisibleTo(userId: UserId) = components.filter { it.isVisibleTo(userId) }
            override fun findUngroupedAfter(afterId: Int, limit: Int) = error("assignment never places Components")
            override fun save(extraction: EpisodeComponents) = extraction.statements.map {
                Component(components.size + 1, it, extraction.episodeId, AccessControl.PUBLIC).also(components::add)
            }
        }
        private val assignEpisode = AssignEpisodeToComponents(
            componentRepository,
            PrepareEpisodeEmbedding(texts, EpisodeCache()),
            PrepareComponentEmbedding(texts, ComponentCache()),
            ComponentExtractor { episode, _ ->
                if (episode.id in failingEpisodes) error("model unavailable")
                statements[episode.id].orEmpty()
            },
        )

        private val unplacedComponents = object : ComponentRepository {
            override fun findCovering(access: AccessControl) = (1..unplaced).map(::unplacedComponent)
            override fun findVisibleTo(userId: UserId) = (1..unplaced).map(::unplacedComponent)
            override fun findUngroupedAfter(afterId: Int, limit: Int) =
                (afterId + 1..unplaced).take(limit).map(::unplacedComponent)
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

        val service = XMemoryUpdateService(
            extractionQueue = extractionQueue,
            extractEpisodes = extractEpisodes,
            outbox = outbox,
            components = unplacedComponents,
            assignEpisode = assignEpisode,
            organizeComponent = OrganizeComponentIntoGroups(
                groups,
                PrepareComponentEmbedding(texts, ComponentCache()),
                GroupOrganizationPlanner(GroupPlacementPolicy(settings), GroupSplitPolicy(settings)),
            ),
            mergeSmallGroups = MergeSmallGroups(groups, GroupMergePolicy(settings)),
            clock = clock,
        )

        /** The completed and failed outbox entries, in the order they were recorded. */
        fun outboxOutcomes() = outboxCalls.filter { it.startsWith("completed") || it.startsWith("failed") }

        private fun unplacedComponent(id: Int) = Component(id, placementStatement(id), id, AccessControl.PUBLIC)
        private fun placementStatement(id: Int) = "placement $id"
        private fun episode(id: Int) = PendingEpisode(id, "title $id", "$id", AccessControl.PUBLIC, listOf("message $id"))
    }

    /** A clock that moves [stepMillis] forward on every read, like a long round of model calls. */
    private class AdvancingClock(private val stepMillis: Long) : Clock() {
        private var millis = NOW
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis).also { millis += stepMillis }
    }

    private class EpisodeCache : EpisodeEmbeddingRepository {
        private val cache = mutableMapOf<EpisodeEmbeddingKey, Embedding<EpisodeEmbeddingKey>>()
        override fun find(key: EpisodeEmbeddingKey) = cache[key]
        override fun save(embedding: Embedding<EpisodeEmbeddingKey>) = cache.getOrPut(embedding.key) { embedding }
    }

    private class ComponentCache : ComponentEmbeddingRepository {
        private val cache = mutableMapOf<ComponentEmbeddingKey, Embedding<ComponentEmbeddingKey>>()
        override fun find(key: ComponentEmbeddingKey) = cache[key]
        override fun save(embedding: Embedding<ComponentEmbeddingKey>) = cache.getOrPut(embedding.key) { embedding }
    }

    private companion object {
        fun source(id: Int) = SourceDescriptor("test", "source $id")
        const val NOW = 1_000_000L
        val SPACE = EmbeddingSpace("test", "v1")
        val VECTOR = EmbeddingVector.normalized(listOf(1.0, 0.0))
    }
}
