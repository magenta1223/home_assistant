package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.OrganizePendingComponents
import com.homeassistant.application.port.input.component.*
import com.homeassistant.application.port.output.group.*
import com.homeassistant.application.port.output.component.*
import com.homeassistant.application.usecase.group.MergeSmallGroups
import com.homeassistant.application.usecase.group.PrepareComponentEmbedding
import com.homeassistant.application.usecase.group.RefreshGroupedComponentEmbeddings
import com.homeassistant.domain.group.*
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.component.ComponentCentroid
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class OrganizePendingEpisodesTest {
    private val space = EmbeddingSpace("test", "v1")
    private val east = ComponentVector.normalized(listOf(1.0, 0.0))
    private val north = ComponentVector.normalized(listOf(0.0, 1.0))
    private val nearEast = ComponentVector.normalized(listOf(1.0, 0.2))

    @Test fun `without components an episode starts one from its own vector`() {
        val fixture = Fixture()
        val result = fixture.service.execute(1)
        assertEquals(EpisodeAssignmentOutcome.Assigned(listOf(100), created = true), result.episodes.single().outcome)
        assertEquals(mapOf(100 to ComponentCentroid.of(east)), fixture.centroids)
        assertEquals(mapOf(100 to listOf(1)), fixture.members)
    }

    @Test fun `an episode joins the nearest component and moves its centroid`() {
        val fixture = Fixture()
        fixture.add(7, ComponentCentroid.of(north), 50)
        fixture.add(9, ComponentCentroid.of(east), 51)
        fixture.vectors = mapOf("1" to nearEast)
        val outcome = fixture.service.execute(1).episodes.single().outcome
        assertEquals(EpisodeAssignmentOutcome.Assigned(listOf(9), created = false), outcome)
        assertEquals(ComponentCentroid.of(east) + nearEast, fixture.centroids.getValue(9))
        assertEquals(listOf(51, 1), fixture.members.getValue(9))
        assertEquals(ComponentCentroid.of(north), fixture.centroids.getValue(7))
    }

    @Test fun `an episode joins every near component in one write`() {
        val fixture = Fixture()
        fixture.add(7, ComponentCentroid.of(north), 50)
        fixture.add(8, ComponentCentroid.of(nearEast), 51)
        fixture.add(9, ComponentCentroid.of(east), 52)
        val outcome = fixture.service.execute(1).episodes.single().outcome
        assertEquals(EpisodeAssignmentOutcome.Assigned(listOf(9, 8), created = false), outcome)
        assertEquals(ComponentCentroid.of(nearEast) + east, fixture.centroids.getValue(8))
        assertEquals(ComponentCentroid.of(east) + east, fixture.centroids.getValue(9))
        assertEquals(listOf(50), fixture.members.getValue(7))
    }

    @Test fun `an episode below the threshold starts a new component`() {
        val fixture = Fixture()
        fixture.add(7, ComponentCentroid.of(north), 50)
        val outcome = fixture.service.execute(1).episodes.single().outcome
        assertEquals(EpisodeAssignmentOutcome.Assigned(listOf(100), created = true), outcome)
        assertEquals(setOf(7, 100), fixture.centroids.keys)
    }

    @Test fun `a join that lost to another write fails that episode only`() {
        val fixture = Fixture()
        fixture.add(7, ComponentCentroid.of(east), 50)
        fixture.losesJoin = setOf(1)
        val result = fixture.service.execute(2)
        assertIs<EpisodeAssignmentOutcome.Failed>(result.episodes.first().outcome)
        assertEquals(EpisodeAssignmentOutcome.Assigned(listOf(7), created = false), result.episodes.last().outcome)
        assertEquals(listOf(50, 2), fixture.members.getValue(7))
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

    @Test fun `moved centroids refresh group vectors before new components are grouped`() {
        val fixture = Fixture()
        fixture.add(7, ComponentCentroid.of(east) + north, 50, 51)
        val old = ComponentEmbedding(PendingComponent(7, "1").embeddingKey(space), east)
        fixture.groupSnapshot = GroupSnapshot(listOf(StoredGroup(Group(3, setOf(7)), 5, east)), mapOf(7 to old),
            listOf(PendingComponent(7, "2")))
        fixture.vectors = mapOf("1" to ComponentVector.normalized(listOf(-1.0, 0.0)))
        val result = fixture.service.execute(1)
        assertEquals(1, result.groupRefreshes.size)
        val change = fixture.groupWrites.single()
        assertEquals(mapOf(3 to 5L), change.expectedGroups)
        assertTrue(change.incomingComponentIds.isEmpty())
        assertEquals(PendingComponent(7, "2").embeddingKey(space), change.embeddings.getValue(7).key)
        assertEquals((ComponentCentroid.of(east) + north).direction, change.embeddings.getValue(7).vector)
        assertEquals(listOf("groups", "merge"), fixture.order.takeLast(2))
    }

    @Test fun `groups are merged only after a round that assigned episodes and placed every component`() {
        val placed = Fixture()
        placed.service.execute(1)
        assertEquals(listOf("groups", "merge"), placed.order.takeLast(2))

        val idle = Fixture()
        idle.claimable = 0
        idle.service.execute(1)
        assertEquals(listOf("refresh", "groups"), idle.order)

        val rebuilding = Fixture()
        rebuilding.placement = GroupOrganizationResult.RebuildRequired(emptyList(), "model changed")
        assertTrue(rebuilding.service.execute(1).groupMerges.isEmpty())
        assertFalse("merge" in rebuilding.order)
    }

    private inner class Fixture {
        var vectors = emptyMap<String, ComponentVector>()
        var groupSnapshot = GroupSnapshot(emptyList(), emptyMap())
        val centroids = linkedMapOf<Int, ComponentCentroid>()
        val members = mutableMapOf<Int, MutableList<Int>>()
        var losesJoin = emptySet<Int>()
        val groupWrites = mutableListOf<GroupChange>()
        val order = mutableListOf<String>()
        val outboxMarks = mutableListOf<String>()
        val claims = mutableListOf<Claim>()
        var embeddingReady = true
        var claimable = 3
        var placement: GroupOrganizationResult = GroupOrganizationResult.Completed(emptyList())

        fun add(componentId: Int, centroid: ComponentCentroid, vararg episodeIds: Int) {
            centroids[componentId] = centroid
            members[componentId] = episodeIds.toMutableList()
        }

        private val episodeCache = mutableMapOf<EpisodeEmbeddingKey, EpisodeEmbedding>()
        private val episodeEmbeddings = PrepareEpisodeEmbedding(object : EpisodeEmbeddingGenerator {
            override val space = this@OrganizePendingEpisodesTest.space
            override fun isAvailable() = embeddingReady
            override fun generate(content: String) = vectors[content] ?: east
        }, object : EpisodeEmbeddingStore {
            override fun find(key: EpisodeEmbeddingKey) = episodeCache[key]
            override fun save(embedding: EpisodeEmbedding) = episodeCache.getOrPut(embedding.key) { embedding }
        })
        private val store = object : ComponentMaintenanceStore {
            override fun centroids() = centroids.toMap()
            override fun read(componentIds: Collection<Int>) = componentIds.filter { it in centroids }
                .map { Component(it, centroids.getValue(it), mapOf(EpisodeAccess.PUBLIC to members.getValue(it).toList())) }
            override fun join(episodeId: Int, joins: List<ComponentJoin>): Boolean {
                if (episodeId in losesJoin || joins.any { centroids[it.componentId] != it.expected }) return false
                joins.forEach {
                    centroids[it.componentId] = it.updated
                    members.getValue(it.componentId).add(episodeId)
                }
                return true
            }
            override fun create(episodeId: Int, centroid: ComponentCentroid): Int {
                val id = 100 + centroids.keys.count { it >= 100 }
                add(id, centroid, episodeId)
                return id
            }
        }
        private val componentCache = mutableMapOf<ComponentEmbeddingKey, ComponentEmbedding>()
        private val componentEmbeddings = PrepareComponentEmbedding(ComponentCentroidVectors(store, space), object : ComponentEmbeddingStore {
            override fun find(key: ComponentEmbeddingKey) = componentCache[key]
            override fun save(embedding: ComponentEmbedding) = componentCache.getOrPut(embedding.key) { embedding }
        })
        private val groupReader = object : GroupSnapshotReader {
            override fun snapshot(): GroupSnapshot { order.add("refresh"); return groupSnapshot }
        }
        private val groupWriter = object : GroupChangeWriter {
            override fun apply(change: GroupChange): GroupWriteResult { groupWrites.add(change); return GroupWriteResult.Applied(listOf(3)) }
        }

        val service = OrganizePendingEpisodesService(
            object : ComponentAssignmentOutbox {
                override fun claimReady(limit: Int, now: Long, retryBefore: Long, staleProcessingBefore: Long, maxAttempts: Int):
                    List<ComponentAssignmentTask> {
                    claims.add(Claim(limit, retryBefore, staleProcessingBefore, maxAttempts))
                    return (1..claimable).take(limit).map { ComponentAssignmentTask(it, 1, PendingEpisode(it, "$it")) }
                }
                override fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long) =
                    outboxMarks.add("completed:$outboxId:$expectedAttempt")
                override fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long) =
                    outboxMarks.add("failed:$outboxId:$expectedAttempt")
                override fun enqueueUnassigned(now: Long) = 3
            },
            AssignEpisodeToComponents(store, episodeEmbeddings),
            RefreshGroupedComponentEmbeddings(groupReader, groupWriter, componentEmbeddings),
            OrganizePendingComponents { order.add("groups"); placement },
            MergeSmallGroups(object : GroupSnapshotReader {
                override fun snapshot(): GroupSnapshot { order.add("merge"); return GroupSnapshot(emptyList(), emptyMap()) }
            }, groupWriter, GroupMergePolicy(GroupPolicySettings())),
            Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC),
        )
    }

    private data class Claim(val limit: Int, val retryBefore: Long, val staleBefore: Long, val maxAttempts: Int)
}
