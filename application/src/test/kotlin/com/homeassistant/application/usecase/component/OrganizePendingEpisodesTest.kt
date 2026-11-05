package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.inbound.memoryextraction.GroupOrganizationResult
import com.homeassistant.application.port.inbound.memoryextraction.OrganizePendingComponents
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAssignmentOutcome
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentAssignmentOutbox
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentAssignmentTask
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentKnowledgeExtractor
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentReader
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentWriter
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbedding
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingGenerator
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingStore
import com.homeassistant.application.port.outbound.memoryextraction.component.PendingEpisode
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbedding
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingStore
import com.homeassistant.application.port.outbound.memoryextraction.group.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChange
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChangeWriter
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupSnapshot
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupSnapshotReader
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupWriteResult
import com.homeassistant.application.usecase.memoryextraction.component.ComponentStatementVectors
import com.homeassistant.application.usecase.memoryextraction.component.ExtractComponents
import com.homeassistant.application.usecase.memoryextraction.component.OrganizePendingEpisodesService
import com.homeassistant.application.usecase.memoryextraction.component.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.memoryextraction.group.MergeSmallGroups
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
import com.homeassistant.domain.group.*
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.component.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class OrganizePendingEpisodesTest {
    private val space = EmbeddingSpace("test", "v1")
    private val east = ComponentVector.normalized(listOf(1.0, 0.0))
    private val north = ComponentVector.normalized(listOf(0.0, 1.0))
    private val nearEast = ComponentVector.normalized(listOf(1.0, 0.2))
    private val onlyB = EpisodeAccess.restricted(listOf(UserId("b")))

    @Test fun `without components every long-term statement of the episode becomes a component`() {
        val fixture = Fixture()
        fixture.statements = mapOf(1 to listOf("홍승민은 임신기간 단축근로를 한다", " ", "홍승민은 임신기간 단축근로를 한다"))
        val result = fixture.service.execute(1)
        assertEquals(EpisodeAssignmentOutcome.Extracted(listOf(1)), result.episodes.single().outcome)
        assertEquals(listOf(Extraction(1, emptyList())), fixture.extractions)
        assertEquals(listOf(Component(1, "홍승민은 임신기간 단축근로를 한다", 1, EpisodeAccess.PUBLIC)), fixture.components)
        assertEquals(listOf("completed:1:1"), fixture.outboxMarks)
    }

    @Test fun `known knowledge is the nearest components every reader of the episode can read`() {
        val fixture = Fixture(knownLimit = 2)
        fixture.add("north fact", north)
        fixture.add("east fact", east)
        fixture.add("near east fact", nearEast)
        fixture.add("hidden east fact", east, onlyB)
        fixture.service.execute(1)
        assertEquals(listOf("east fact", "near east fact"), fixture.extractions.single().known)
    }

    @Test fun `an episode with no new knowledge is completed without components`() {
        val fixture = Fixture()
        val result = fixture.service.execute(1)
        assertEquals(EpisodeAssignmentOutcome.Extracted(emptyList()), result.episodes.single().outcome)
        assertEquals(setOf(1), fixture.extracted)
        assertEquals(listOf("completed:1:1"), fixture.outboxMarks)
    }

    @Test fun `a failed extraction fails that episode only`() {
        val fixture = Fixture()
        fixture.failing = setOf(1)
        fixture.statements = mapOf(2 to listOf("fact"))
        val result = fixture.service.execute(2)
        assertIs<EpisodeAssignmentOutcome.Failed>(result.episodes.first().outcome)
        assertEquals(EpisodeAssignmentOutcome.Extracted(listOf(1)), result.episodes.last().outcome)
        assertEquals(setOf(2), fixture.extracted)
        assertEquals(listOf("failed:1:1", "completed:2:1"), fixture.outboxMarks)
    }

    @Test fun `claims use the retry delay, processing lease and attempt limit`() {
        val fixture = Fixture()
        fixture.service.execute(2)
        assertEquals(Claim(2, 1_000_000L - 60_000L, 1_000_000L - 600_000L, 5), fixture.claims.single())
    }

    @Test fun `readiness comes from the episode embedding`() {
        val fixture = Fixture()
        assertTrue(fixture.service.isReady())
        fixture.embeddingReady = false
        assertFalse(fixture.service.isReady())
    }

    @Test fun `queuing unassigned episodes is delegated to the outbox`() {
        val fixture = Fixture()
        assertEquals(3, fixture.service.enqueueUnassigned())
    }

    @Test fun `groups are merged only after a round that extracted episodes and placed every component`() {
        val placed = Fixture()
        placed.service.execute(1)
        assertEquals(listOf("groups", "merge"), placed.order.takeLast(2))

        val idle = Fixture()
        idle.claimable = 0
        idle.service.execute(1)
        assertEquals(listOf("groups"), idle.order)

        val rebuilding = Fixture()
        rebuilding.placement = GroupOrganizationResult.RebuildRequired(emptyList(), "model changed")
        assertTrue(rebuilding.service.execute(1).groupMerges.isEmpty())
        assertFalse("merge" in rebuilding.order)
    }

    private inner class Fixture(knownLimit: Int = 10) {
        var vectors = emptyMap<String, ComponentVector>()
        var statements = emptyMap<Int, List<String>>()
        var failing = emptySet<Int>()
        val components = mutableListOf<Component>()
        val extracted = mutableSetOf<Int>()
        val extractions = mutableListOf<Extraction>()
        val groupWrites = mutableListOf<GroupChange>()
        val order = mutableListOf<String>()
        val outboxMarks = mutableListOf<String>()
        val claims = mutableListOf<Claim>()
        var embeddingReady = true
        var claimable = 3
        var placement: GroupOrganizationResult = GroupOrganizationResult.Completed(emptyList())

        fun add(statement: String, vector: ComponentVector, access: EpisodeAccess = EpisodeAccess.PUBLIC): Component {
            vectors = vectors + (statement to vector)
            return Component(components.size + 1, statement, 50 + components.size, access).also(components::add)
        }

        private val texts = object : EpisodeEmbeddingGenerator {
            override val space = this@OrganizePendingEpisodesTest.space
            override fun isAvailable() = embeddingReady
            override fun generate(content: String) = vectors[content] ?: east
        }
        private val episodeCache = mutableMapOf<EpisodeEmbeddingKey, EpisodeEmbedding>()
        private val episodeEmbeddings = PrepareEpisodeEmbedding(texts, object : EpisodeEmbeddingStore {
            override fun find(key: EpisodeEmbeddingKey) = episodeCache[key]
            override fun save(embedding: EpisodeEmbedding) = episodeCache.getOrPut(embedding.key) { embedding }
        })
        private val componentReader = object : ComponentReader {
            override fun all() = components.toList()
            override fun read(componentIds: Collection<Int>) = components.filter { it.id in componentIds }
        }

        private val componentWriter = object : ComponentWriter {
            override fun writeComponents(episodeId: Int, statements: List<String>): List<Int> {
                check(extracted.add(episodeId))
                return statements.map { Component(components.size + 1, it, episodeId, EpisodeAccess.PUBLIC).also(components::add).id }
            }
        }
        private val componentCache = mutableMapOf<ComponentEmbeddingKey, ComponentEmbedding>()
        private val componentEmbeddings = PrepareComponentEmbedding(ComponentStatementVectors(componentReader, texts), object :
            ComponentEmbeddingStore {
            override fun find(key: ComponentEmbeddingKey) = componentCache[key]
            override fun save(embedding: ComponentEmbedding) = componentCache.getOrPut(embedding.key) { embedding }
        })
        private val extractor = ComponentKnowledgeExtractor { episode, known ->
            extractions.add(Extraction(episode.id, known))
            if (episode.id in failing) error("model unavailable")
            statements[episode.id].orEmpty()
        }
        private val groupWriter = object : GroupChangeWriter {
            override fun apply(change: GroupChange): GroupWriteResult { groupWrites.add(change); return GroupWriteResult.Applied(listOf(3)) }
        }

        val service = OrganizePendingEpisodesService(
            object : ComponentAssignmentOutbox {
                override fun claimReady(
                    limit: Int,
                    now: Long,
                    retryBefore: Long,
                    staleProcessingBefore: Long,
                    maxAttempts: Int
                ):
                        List<ComponentAssignmentTask> {
                    claims.add(Claim(limit, retryBefore, staleProcessingBefore, maxAttempts))
                    return (1..claimable).take(limit).map {
                        ComponentAssignmentTask(
                            it,
                            1,
                            PendingEpisode(it, "title $it", "$it", EpisodeAccess.PUBLIC, listOf("message $it"))
                        )
                    }
                }

                override fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long) =
                    outboxMarks.add("completed:$outboxId:$expectedAttempt")

                override fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long) =
                    outboxMarks.add("failed:$outboxId:$expectedAttempt")

                override fun enqueueUnassigned(now: Long) = 3
            },
            ExtractComponents(componentReader, componentWriter, episodeEmbeddings, componentEmbeddings, extractor, knownLimit),
            OrganizePendingComponents { order.add("groups"); placement },
            MergeSmallGroups(object : GroupSnapshotReader {
                override fun snapshot(): GroupSnapshot {
                    order.add("merge"); return GroupSnapshot(
                        emptyList(),
                        emptyMap()
                    )
                }
            }, groupWriter, GroupMergePolicy(GroupPolicySettings())),
            Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC),
        )
    }

    private data class Extraction(val episodeId: Int, val known: List<String>)

    private data class Claim(val limit: Int, val retryBefore: Long, val staleBefore: Long, val maxAttempts: Int)
}
