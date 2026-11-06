package com.homeassistant.application.usecase.group

import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentRepository
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeComponents
import com.homeassistant.domain.identity.UserId
import com.homeassistant.application.port.inbound.memoryextraction.ComponentOrganizationOutcome
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbedding
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingStore
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChange
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChangeWriter
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupSnapshot
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupSnapshotReader
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupTarget
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupWriteResult
import com.homeassistant.application.port.outbound.memoryextraction.group.PendingComponent
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRebuildRequired
import com.homeassistant.application.port.outbound.memoryextraction.group.StoredGroup
import com.homeassistant.application.usecase.memoryextraction.group.OrganizeComponentIntoGroups
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.group.*
import java.util.concurrent.CancellationException
import kotlin.test.*

class OrganizeComponentIntoGroupsTest {
    private val space = EmbeddingSpace("test", "v1")
    private val vector = ComponentVector.normalized(listOf(1.0, 0.0))

    @Test fun `a failed embedding writes nothing`() {
        val fixture = Fixture()
        fixture.generate = { error("unavailable") }
        assertFails { fixture.organize(1) }
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun `conflict rereads snapshot and replans with a new key without regenerating embedding`() {
        val fixture = Fixture()
        fixture.write = { if (fixture.writes.size == 1) GroupWriteResult.Conflict else GroupWriteResult.Applied(listOf(10)) }
        assertIs<ComponentOrganizationOutcome.Organized>(fixture.organize(1))
        assertEquals(2, fixture.reads)
        assertEquals(1, fixture.generated)
        assertEquals(2, fixture.writes.map { it.operationKey }.distinct().size)
    }

    @Test fun `conflicts are bounded and cached vectors survive retry calls`() {
        val fixture = Fixture()
        fixture.write = { GroupWriteResult.Conflict }
        repeat(2) { assertIs<ComponentOrganizationOutcome.ConflictExhausted>(fixture.organize(1)) }
        assertEquals(6, fixture.writes.size)
        assertEquals(1, fixture.generated)
    }

    @Test fun `concurrent winner is observed after conflict`() {
        val fixture = Fixture()
        fixture.write = {
            val embedding = fixture.cache.values.single()
            fixture.snapshot = GroupSnapshot(listOf(StoredGroup(Group(4, setOf(1)), 1, vector)), mapOf(1 to embedding))
            GroupWriteResult.Conflict
        }
        assertIs<ComponentOrganizationOutcome.AlreadyOrganized>(fixture.organize(1))
        assertEquals(1, fixture.writes.size)
    }

    @Test fun `model change is reported before any embedding or write`() {
        val fixture = Fixture()
        val embedding = ComponentEmbedding(PendingComponent(9, "9").embeddingKey(EmbeddingSpace("old", "v1")), vector)
        fixture.snapshot = GroupSnapshot(listOf(StoredGroup(Group(4, setOf(9)), 1, vector)), mapOf(9 to embedding))
        assertFailsWith<GroupRebuildRequired> { fixture.organize(1) }
        assertEquals(0, fixture.generated)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun `dimension change is reported and cancellation propagates`() {
        val fixture = Fixture()
        fixture.snapshot = GroupSnapshot(
            listOf(StoredGroup(Group(4, setOf(9)), 1, vector)),
            mapOf(9 to ComponentEmbedding(PendingComponent(9, "9").embeddingKey(space), vector))
        )
        fixture.generate = { ComponentVector.normalized(listOf(1.0)) }
        assertFailsWith<GroupRebuildRequired> { fixture.organize(1) }
        val cancelled = Fixture()
        cancelled.generate = { throw CancellationException("cancel") }
        assertFailsWith<CancellationException> { cancelled.organize(1) }
    }

    @Test fun `attachment and split are one change preserving the old group id`() {
        val fixture = Fixture(maxComponents = 2)
        fixture.snapshot = GroupSnapshot(
            listOf(StoredGroup(Group(4, setOf(8, 9)), 7, vector)),
            (8..9).associateWith { ComponentEmbedding(PendingComponent(it, "$it").embeddingKey(space), vector) })
        assertIs<GroupSplit.Divided>(assertIs<ComponentOrganizationOutcome.Organized>(fixture.organize(1)).split)
        val change = fixture.writes.single()
        assertEquals(mapOf(4 to 7L), change.expectedGroups)
        assertEquals(setOf(1, 8, 9), change.resultingGroups.flatMap { it.componentIds }.toSet())
        assertEquals(GroupTarget.Existing(4), change.resultingGroups.first { 1 in it.componentIds }.target)
        assertEquals(2, change.resultingGroups.size)
    }

    private inner class Fixture(maxComponents: Int = 12) {
        var snapshot = GroupSnapshot(emptyList(), emptyMap())
        val cache = mutableMapOf<ComponentEmbeddingKey, ComponentEmbedding>()
        val writes = mutableListOf<GroupChange>()
        var reads = 0
        var generated = 0
        var generate: (String) -> ComponentVector = { vector }
        var write: (GroupChange) -> GroupWriteResult = { GroupWriteResult.Applied(listOf(10)) }
        private val components = object : ComponentRepository {
            override fun findByIds(ids: Collection<Int>) = ids.map { Component(it, "$it", it, EpisodeAccess.PUBLIC) }
            override fun findCovering(access: EpisodeAccess) = emptyList<Component>()
            override fun findVisibleTo(userId: UserId) = emptyList<Component>()
            override fun save(extraction: EpisodeComponents) = error("organization never saves Components")
        }
        private val generator = object : EmbeddingGenerator {
            override val space = this@OrganizeComponentIntoGroupsTest.space
            override fun isAvailable() = true
            override fun embedDocument(text: String): ComponentVector { generated++; return generate.invoke(text) }
            override fun embedQuery(text: String) = vector
        }
        private val store = object : ComponentEmbeddingStore {
            override fun find(key: ComponentEmbeddingKey) = cache[key]
            override fun save(embedding: ComponentEmbedding) = cache.getOrPut(embedding.key) { embedding }
        }
        private val reader = object : GroupSnapshotReader {
            override fun snapshot(): GroupSnapshot { reads++; return snapshot }
        }
        private val writer = object : GroupChangeWriter {
            override fun apply(change: GroupChange): GroupWriteResult { writes.add(change); return write(change) }
        }
        private val service = OrganizeComponentIntoGroups(
            reader, writer, PrepareComponentEmbedding(components, generator, store),
            GroupOrganizationPlanner(
                GroupPlacementPolicy(GroupPolicySettings(maxComponents = maxComponents)),
                GroupSplitPolicy(GroupPolicySettings(maxComponents = maxComponents))
            )
        )

        fun organize(id: Int) = service.organize(PendingComponent(id, "$id"))
    }
}
