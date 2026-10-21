package com.homeassistant.application.usecase.group

import com.homeassistant.application.port.input.group.GroupRefreshOutcome
import com.homeassistant.application.port.output.group.*
import com.homeassistant.domain.group.GroupGeometry
import java.util.UUID

/**
 * Re-prepares grouped topics whose vector version changed and replaces only the vectors and centroid of
 * their Group. Membership never moves; placement remains the job of [OrganizeTopic].
 */
class RefreshGroupedTopicEmbeddings(
    private val snapshots: GroupSnapshotReader,
    private val writer: GroupChangeWriter,
    private val embeddings: PrepareTopicEmbedding,
    private val maxAttempts: Int = 3,
) {
    init { require(maxAttempts > 0) }

    fun execute(): List<GroupRefreshOutcome> {
        val snapshot = snapshots.snapshot()
        val outdatedIds = snapshot.topicsWithOutdatedGroupVector.map { it.id }.toSet()
        return snapshot.groups.filter { stored -> stored.group.topicIds.any { it in outdatedIds } }
            .mapNotNull { refresh(it.group.id) }
    }

    /** Returns null when a concurrent change already removed the group or renewed its outdated topic vectors. */
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
        val topicIds = stored.group.topicIds
        val outdated = snapshot.topicsWithOutdatedGroupVector.filter { it.id in topicIds }
        if (outdated.isEmpty()) return null
        val renewed = snapshot.embeddings.filterKeys { it in topicIds } + outdated.associate { it.id to embeddings.prepare(it) }
        val centroid = GroupGeometry.centroid(topicIds.sorted().map { renewed.getValue(it).vector })
        return GroupChange(
            UUID.randomUUID().toString(), mapOf(groupId to stored.revision), emptySet(),
            listOf(ResultingGroup(GroupTarget.Existing(groupId), topicIds, centroid)), renewed,
        )
    }
}
