package com.homeassistant.application.usecase.group

import com.homeassistant.application.port.inbound.group.ComponentOrganizationOutcome
import com.homeassistant.application.port.outbound.group.*
import com.homeassistant.domain.group.*
import java.util.UUID

/**
 * Places one pending Component into a Group, splitting it when the planner says so, and retries on a fresh snapshot up to
 * [maxAttempts] times when a concurrent change wins the write.
 */
class OrganizeComponent(
    private val snapshots: GroupSnapshotReader,
    private val writer: GroupChangeWriter,
    private val embeddings: PrepareComponentEmbedding,
    private val planner: GroupOrganizationPlanner,
    private val maxAttempts: Int = 3,
) {
    init { require(maxAttempts > 0) }

    /**
     * Places [component] and returns the Groups it changed, or why nothing was written.
     * @throws GroupRebuildRequired when stored Group vectors are outdated or come from another embedding space or dimension.
     */
    fun execute(component: PendingComponent): ComponentOrganizationOutcome {
        val initial = snapshots.snapshot()
        validateSpace(initial)
        if (initial.embeddings.containsKey(component.id)) return ComponentOrganizationOutcome.AlreadyOrganized
        val embedding = embeddings.prepare(component)
        repeat(maxAttempts) { attempt ->
            val snapshot = if (attempt == 0) initial else snapshots.snapshot()
            validateSpace(snapshot)
            if (snapshot.embeddings.containsKey(component.id)) return ComponentOrganizationOutcome.AlreadyOrganized
            validateDimension(snapshot, embedding)
            val allEmbeddings = snapshot.embeddings + (component.id to embedding)
            val decision = planner.plan(component.id, snapshot.groups.map { it.group },
                snapshot.groups.associate { it.group.id to it.centroid }, allEmbeddings.mapValues { it.value.vector })
            val change = changeFor(snapshot, embedding, decision)
            when (val result = writer.apply(change)) {
                is GroupWriteResult.Applied -> return ComponentOrganizationOutcome.Organized(result.groupIds, decision.split)
                is GroupWriteResult.AlreadyApplied -> return ComponentOrganizationOutcome.Organized(result.groupIds, decision.split)
                GroupWriteResult.Conflict -> Unit
            }
        }
        return ComponentOrganizationOutcome.ConflictExhausted
    }

    private fun validateSpace(snapshot: GroupSnapshot) {
        if (snapshot.embeddings.values.any { it.key.space != embeddings.space }) {
            throw GroupRebuildRequired("group embeddings use a different model or input format")
        }
    }

    private fun validateDimension(snapshot: GroupSnapshot, incoming: ComponentEmbedding) {
        if (snapshot.embeddings.values.any { it.vector.dimension != incoming.vector.dimension }) {
            throw GroupRebuildRequired("group embedding dimension changed")
        }
    }

    private fun changeFor(snapshot: GroupSnapshot, incoming: ComponentEmbedding, decision: GroupOrganizationPlan): GroupChange {
        val selected = snapshot.groups.filter { it.group.id in decision.consumedGroupIds }
        val componentIds = decision.partition.parts.flatten().toSet()
        val allEmbeddings = snapshot.embeddings + (incoming.key.componentId to incoming)
        val results = decision.partition.parts.mapIndexed { index, ids ->
            val target = if (index == 0 && selected.isNotEmpty()) GroupTarget.Existing(selected.single().group.id)
                else GroupTarget.Created("part-$index")
            ResultingGroup(target, ids, GroupGeometry.centroid(ids.sorted().map { allEmbeddings.getValue(it).vector }))
        }
        return GroupChange(
            UUID.randomUUID().toString(), selected.associate { it.group.id to it.revision },
            setOf(incoming.key.componentId), results, allEmbeddings.filterKeys { it in componentIds },
        )
    }
}
