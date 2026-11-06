package com.homeassistant.application.usecase.memoryextraction.group

import com.homeassistant.application.port.inbound.memoryextraction.GroupMergeOutcome
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChange
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChangeConflict
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupSnapshot
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRepository
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupTarget
import com.homeassistant.application.port.outbound.memoryextraction.group.ResultingGroup
import com.homeassistant.domain.embedding.EmbeddingGeometry
import com.homeassistant.domain.group.GroupMerge
import com.homeassistant.domain.group.GroupMergePolicy
import java.util.UUID

/**
 * Merges single-Component Groups into neighbors, one merge at a time on a fresh snapshot, until the policy finds none or
 * [maxConflicts] writes lost to concurrent changes. Every merge removes a Group, so the loop ends.
 * It must run after Group vectors were refreshed: scores from outdated Component vectors would merge the wrong Groups.
 */
class MergeSmallGroups(
    private val groups: GroupRepository,
    private val policy: GroupMergePolicy,
    private val maxConflicts: Int = 3,
) {
    init { require(maxConflicts > 0) }

    /**
     * Merges until no single-Component Group has a neighbor worth merging into, and returns each merge in order, ending with
     * [GroupMergeOutcome.ConflictExhausted] when concurrent changes stopped it.
     * @throws IllegalStateException when a Group still holds an outdated Component vector.
     */
    fun execute(): List<GroupMergeOutcome> {
        val outcomes = mutableListOf<GroupMergeOutcome>()
        var conflicts = 0
        while (true) {
            val snapshot = groups.findSnapshot()
            val merge = policy.decide(
                snapshot.groups.map { it.group },
                snapshot.groups.associate { it.group.id to it.centroid },
                snapshot.embeddings.mapValues { it.value.vector },
            ) ?: break
            runCatching { groups.save(changeFor(snapshot, merge)) }.fold(
                onSuccess = { outcomes += GroupMergeOutcome.Merged(merge.survivor, merge.absorbed) },
                onFailure = { error ->
                    if (error !is GroupChangeConflict) throw error
                    if (++conflicts == maxConflicts) {
                        outcomes += GroupMergeOutcome.ConflictExhausted
                        break
                    }
                },
            )
        }
        return outcomes
    }

    private fun changeFor(snapshot: GroupSnapshot, merge: GroupMerge): GroupChange {
        val selected = snapshot.groups.filter { it.group.id == merge.survivor || it.group.id == merge.absorbed }
        val componentIds = selected.flatMap { it.group.componentIds }.toSet()
        val embeddings = snapshot.embeddings.filterKeys { it in componentIds }
        return GroupChange(
            UUID.randomUUID().toString(), selected.associate { it.group.id to it.revision }, emptySet(),
            listOf(
                ResultingGroup(
                    GroupTarget.Existing(merge.survivor), componentIds,
                    EmbeddingGeometry.centroid(componentIds.sorted().map { embeddings.getValue(it).vector })
                )
            ),
            embeddings,
        )
    }
}
