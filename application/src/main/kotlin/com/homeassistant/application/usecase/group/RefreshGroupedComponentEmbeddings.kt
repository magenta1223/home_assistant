package com.homeassistant.application.usecase.group

import com.homeassistant.application.port.input.group.GroupRefreshOutcome
import com.homeassistant.application.port.output.group.*
import com.homeassistant.domain.group.GroupGeometry
import java.util.UUID

/**
 * Re-prepares grouped components whose vector version changed and replaces only the vectors and centroid of
 * their Group. Membership never moves; placement remains the job of [OrganizeComponent].
 */
class RefreshGroupedComponentEmbeddings(
    private val snapshots: GroupSnapshotReader,
    private val writer: GroupChangeWriter,
    private val embeddings: PrepareComponentEmbedding,
    private val maxAttempts: Int = 3,
) {
    init { require(maxAttempts > 0) }

    /**
     * Refreshes every Group holding an outdated Component vector and returns one outcome per Group written or given up on.
     * Groups a concurrent change already removed or renewed are left out.
     */
    fun execute(): List<GroupRefreshOutcome> {
        val snapshot = snapshots.snapshot()
        val outdatedIds = snapshot.componentsWithOutdatedGroupVector.map { it.id }.toSet()
        return snapshot.groups.filter { stored -> stored.group.componentIds.any { it in outdatedIds } }
            .mapNotNull { refresh(it.group.id) }
    }

    /** Returns null when a concurrent change already removed the group or renewed its outdated component vectors. */
    private fun refresh(groupId: Int): GroupRefreshOutcome? {
        repeat(maxAttempts) {
            val snapshot = snapshots.snapshot()
            val change = changeFor(snapshot, groupId) ?: return null
            when (writer.apply(change)) {
                is GroupWriteResult.Applied, is GroupWriteResult.AlreadyApplied -> return GroupRefreshOutcome.Refreshed(groupId)
                GroupWriteResult.Conflict -> Unit
            }
        }
        return GroupRefreshOutcome.ConflictExhausted(groupId)
    }

    private fun changeFor(snapshot: GroupSnapshot, groupId: Int): GroupChange? {
        val stored = snapshot.groups.singleOrNull { it.group.id == groupId } ?: return null
        val componentIds = stored.group.componentIds
        val outdated = snapshot.componentsWithOutdatedGroupVector.filter { it.id in componentIds }
        if (outdated.isEmpty()) return null
        val renewed = snapshot.embeddings.filterKeys { it in componentIds } + outdated.associate { it.id to embeddings.prepare(it) }
        val centroid = GroupGeometry.centroid(componentIds.sorted().map { renewed.getValue(it).vector })
        return GroupChange(
            UUID.randomUUID().toString(), mapOf(groupId to stored.revision), emptySet(),
            listOf(ResultingGroup(GroupTarget.Existing(groupId), componentIds, centroid)), renewed,
        )
    }
}
