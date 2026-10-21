package com.homeassistant.application.usecase.group

import com.homeassistant.application.port.input.group.GroupMergeOutcome
import com.homeassistant.application.port.output.group.*
import com.homeassistant.domain.group.*
import kotlin.test.*

class MergeSmallGroupsTest {
    private val space = EmbeddingSpace("test", "v1")
    private val east = TopicVector.normalized(listOf(1.0, 0.0))

    @Test fun `single-topic groups merge one at a time on fresh snapshots until none is left`() {
        val store = Store(mapOf(1 to setOf(1, 2), 2 to setOf(3), 3 to setOf(4), 4 to setOf(5, 6), 5 to setOf(7), 6 to setOf(8)))

        val outcomes = merger(store).execute()

        assertEquals(listOf(GroupMergeOutcome.Merged(2, 3), GroupMergeOutcome.Merged(5, 6)), outcomes)
        assertEquals(mapOf(1 to setOf(1, 2), 2 to setOf(3, 4), 4 to setOf(5, 6), 5 to setOf(7, 8)), store.groups)
        assertEquals(mapOf(2 to 1L, 3 to 1L), store.writes.first().expectedGroups)
        assertEquals(3, store.reads)
    }

    @Test fun `merging before group vectors are refreshed fails without writing`() {
        val store = Store(mapOf(1 to setOf(1, 2), 2 to setOf(3)))
        store.outdated = listOf(PendingTopic(1, "2"))

        assertFailsWith<IllegalStateException> { merger(store).execute() }
        assertTrue(store.writes.isEmpty())
    }

    @Test fun `conflicts are retried on a new snapshot and bounded`() {
        val store = Store(mapOf(1 to setOf(1, 2), 2 to setOf(3)))
        store.conflicts = Int.MAX_VALUE

        assertEquals(listOf(GroupMergeOutcome.ConflictExhausted), merger(store).execute())
        assertEquals(3, store.writes.size)
        assertEquals(3, store.writes.map { it.operationKey }.distinct().size)
    }

    private fun merger(store: Store) = MergeSmallGroups(store, store, GroupMergePolicy(GroupPolicySettings()))

    private inner class Store(initial: Map<Int, Set<Int>>) : GroupSnapshotReader, GroupChangeWriter {
        val groups = initial.toMutableMap()
        private val revisions = initial.keys.associateWith { 1L }.toMutableMap()
        var outdated = emptyList<PendingTopic>()
        var conflicts = 0
        var reads = 0
        val writes = mutableListOf<GroupChange>()

        override fun snapshot(): GroupSnapshot {
            reads++
            val embeddings = groups.values.flatten().associateWith { TopicEmbedding(PendingTopic(it, "1").embeddingKey(space), east) }
            return GroupSnapshot(groups.map { (id, topics) -> StoredGroup(Group(id, topics), revisions.getValue(id), east) }, embeddings, outdated)
        }

        override fun apply(change: GroupChange): GroupWriteResult {
            writes += change
            if (conflicts-- > 0) return GroupWriteResult.Conflict
            change.expectedGroups.keys.forEach { groups.remove(it); revisions.remove(it) }
            val ids = change.resultingGroups.map { result ->
                val id = (result.target as GroupTarget.Existing).id
                groups[id] = result.topicIds
                revisions[id] = change.expectedGroups.getValue(id) + 1
                id
            }
            return GroupWriteResult.Applied(ids)
        }
    }
}
