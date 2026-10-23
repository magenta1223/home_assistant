package com.homeassistant.application.usecase.group

import com.homeassistant.application.port.input.group.GroupMergeOutcome
import com.homeassistant.application.port.output.group.*
import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.GroupMerge
import com.homeassistant.domain.group.GroupMergePolicy
import java.util.UUID

/**
 * Merges single-Topic Groups into neighbors, one merge at a time on a fresh snapshot, until the policy finds none or
 * [maxConflicts] writes lost to concurrent changes. Every merge removes a Group, so the loop ends.
 * It must run after Group vectors were refreshed: scores from outdated Topic vectors would merge the wrong Groups.
 */
class MergeSmallGroups(
    private val snapshots: GroupSnapshotReader,
    private val writer: GroupChangeWriter,
    private val policy: GroupMergePolicy,
    private val maxConflicts: Int = 3,
) {
    init { require(maxConflicts > 0) }

    /**
     * Merges until no single-Topic Group has a neighbor worth merging into, and returns each merge in order, ending with
     * [GroupMergeOutcome.ConflictExhausted] when concurrent changes stopped it.
     * @throws IllegalStateException when a Group still holds an outdated Topic vector.
     */
    fun execute(): List<GroupMergeOutcome> {
        val outcomes = mutableListOf<GroupMergeOutcome>()
        var conflicts = 0
        while (true) {
            val snapshot = snapshots.snapshot()
            check(snapshot.topicsWithOutdatedGroupVector.isEmpty()) { "group topic vectors must be refreshed before merging" }
            val merge = policy.decide(
                snapshot.groups.map { it.group },
                snapshot.groups.associate { it.group.id to it.centroid },
                snapshot.embeddings.mapValues { it.value.vector },
            ) ?: break
            when (writer.apply(changeFor(snapshot, merge))) {
                is GroupWriteResult.Applied, is GroupWriteResult.AlreadyApplied ->
                    outcomes += GroupMergeOutcome.Merged(merge.survivor, merge.absorbed)
                GroupWriteResult.Conflict -> if (++conflicts == maxConflicts) {
                    outcomes += GroupMergeOutcome.ConflictExhausted
                    break
                }
            }
        }
        return outcomes
    }

    private fun changeFor(snapshot: GroupSnapshot, merge: GroupMerge): GroupChange {
        val selected = snapshot.groups.filter { it.group.id == merge.survivor || it.group.id == merge.absorbed }
        val topicIds = selected.flatMap { it.group.topicIds }.toSet()
        val embeddings = snapshot.embeddings.filterKeys { it in topicIds }
        return GroupChange(
            UUID.randomUUID().toString(), selected.associate { it.group.id to it.revision }, emptySet(),
            listOf(ResultingGroup(GroupTarget.Existing(merge.survivor), topicIds,
                GroupGeometry.centroid(topicIds.sorted().map { embeddings.getValue(it).vector }))),
            embeddings,
        )
    }
}
