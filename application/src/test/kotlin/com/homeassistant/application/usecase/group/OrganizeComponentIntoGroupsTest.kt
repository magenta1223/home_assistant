package com.homeassistant.application.usecase.group

import com.homeassistant.domain.embedding.EmbeddingVector
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRepository
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChangeConflict
import com.homeassistant.domain.identity.UserId
import com.homeassistant.application.port.inbound.memoryextraction.ComponentOrganizationOutcome
import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.application.port.outbound.embedding.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.embedding.ComponentEmbeddingRepository
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChange
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupSnapshot
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupTarget
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRebuildRequired
import com.homeassistant.application.port.outbound.memoryextraction.group.StoredGroup
import com.homeassistant.application.usecase.memoryextraction.group.OrganizeComponentIntoGroups
import com.homeassistant.application.usecase.embedding.PrepareComponentEmbedding
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.group.*
import java.util.concurrent.CancellationException
import kotlin.test.*

class OrganizeComponentIntoGroupsTest {
    private val space = EmbeddingSpace("test", "v1")
    private val vector = EmbeddingVector.normalized(listOf(1.0, 0.0))

    @Test fun `a failed embedding writes nothing`() {
        val fixture = Fixture()
        fixture.generate = { error("unavailable") }
        assertFails { fixture.organize(1) }
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun `conflict rereads snapshot and replans without regenerating embedding`() {
        val fixture = Fixture()
        fixture.write = { if (fixture.writes.size == 1) throw GroupChangeConflict("lost") else saved(it) }
        assertIs<ComponentOrganizationOutcome.Organized>(fixture.organize(1))
        assertEquals(2, fixture.reads)
        assertEquals(1, fixture.generated)
    }

    @Test fun `conflicts are bounded and cached vectors survive retry calls`() {
        val fixture = Fixture()
        fixture.write = { throw GroupChangeConflict("lost") }
        repeat(2) { assertIs<ComponentOrganizationOutcome.ConflictExhausted>(fixture.organize(1)) }
        assertEquals(6, fixture.writes.size)
        assertEquals(1, fixture.generated)
    }

    @Test fun `concurrent winner is observed after conflict`() {
        val fixture = Fixture()
        fixture.write = {
            val embedding = fixture.cache.values.single()
            fixture.snapshot = GroupSnapshot(listOf(StoredGroup(Group(4, setOf(1)), 1, vector)), mapOf(1 to embedding))
            throw GroupChangeConflict("lost")
        }
        assertIs<ComponentOrganizationOutcome.AlreadyOrganized>(fixture.organize(1))
        assertEquals(1, fixture.writes.size)
    }

    @Test fun `model change is reported before any embedding or write`() {
        val fixture = Fixture()
        val embedding = Embedding(ComponentEmbeddingKey(9, "9", EmbeddingSpace("old", "v1")), vector)
        fixture.snapshot = GroupSnapshot(listOf(StoredGroup(Group(4, setOf(9)), 1, vector)), mapOf(9 to embedding))
        assertFailsWith<GroupRebuildRequired> { fixture.organize(1) }
        assertEquals(0, fixture.generated)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun `dimension change is reported and cancellation propagates`() {
        val fixture = Fixture()
        fixture.snapshot = GroupSnapshot(
            listOf(StoredGroup(Group(4, setOf(9)), 1, vector)),
            mapOf(9 to Embedding(ComponentEmbeddingKey(9, "9", space), vector))
        )
        fixture.generate = { EmbeddingVector.normalized(listOf(1.0)) }
        assertFailsWith<GroupRebuildRequired> { fixture.organize(1) }
        val cancelled = Fixture()
        cancelled.generate = { throw CancellationException("cancel") }
        assertFailsWith<CancellationException> { cancelled.organize(1) }
    }

    @Test fun `attachment and split are one change preserving the old group id`() {
        val fixture = Fixture(maxComponents = 2)
        fixture.snapshot = GroupSnapshot(
            listOf(StoredGroup(Group(4, setOf(8, 9)), 7, vector)),
            (8..9).associateWith { Embedding(ComponentEmbeddingKey(it, "$it", space), vector) })
        assertIs<GroupSplit.Divided>(assertIs<ComponentOrganizationOutcome.Organized>(fixture.organize(1)).split)
        val change = fixture.writes.single()
        assertEquals(mapOf(4 to 7L), change.expectedGroups)
        assertEquals(setOf(1, 8, 9), change.resultingGroups.flatMap { it.componentIds }.toSet())
        assertEquals(GroupTarget.Existing(4), change.resultingGroups.first { 1 in it.componentIds }.target)
        assertEquals(2, change.resultingGroups.size)
    }

    private inner class Fixture(maxComponents: Int = 12) {
        var snapshot = GroupSnapshot(emptyList(), emptyMap())
        val cache = mutableMapOf<ComponentEmbeddingKey, Embedding<ComponentEmbeddingKey>>()
        val writes = mutableListOf<GroupChange>()
        var reads = 0
        var generated = 0
        var generate: (String) -> EmbeddingVector = { vector }
        var write: (GroupChange) -> List<Group> = ::saved
        private val generator = object : EmbeddingGenerator {
            override val space = this@OrganizeComponentIntoGroupsTest.space
            override fun isAvailable() = true
            override fun embedDocument(text: String): EmbeddingVector { generated++; return generate.invoke(text) }
            override fun embedQuery(text: String) = vector
        }
        private val store = object : ComponentEmbeddingRepository {
            override fun find(key: ComponentEmbeddingKey) = cache[key]
            override fun save(embedding: Embedding<ComponentEmbeddingKey>) = cache.getOrPut(embedding.key) { embedding }
        }
        private val groups = object : GroupRepository {
            override fun findVisibleTo(userId: UserId) = error("organization never searches Groups")
            override fun findSnapshot(): GroupSnapshot { reads++; return snapshot }
            override fun save(change: GroupChange): List<Group> { writes.add(change); return write(change) }
        }
        private val service = OrganizeComponentIntoGroups(
            groups, PrepareComponentEmbedding(generator, store),
            GroupOrganizationPlanner(
                GroupPlacementPolicy(GroupPolicySettings(maxComponents = maxComponents)),
                GroupSplitPolicy(GroupPolicySettings(maxComponents = maxComponents))
            )
        )

        fun organize(id: Int) = service.organize(Component(id, "$id", id, AccessControl.PUBLIC))
    }

    private fun saved(change: GroupChange) = change.resultingGroups.mapIndexed { index, result -> Group(10 + index, result.componentIds) }
}
