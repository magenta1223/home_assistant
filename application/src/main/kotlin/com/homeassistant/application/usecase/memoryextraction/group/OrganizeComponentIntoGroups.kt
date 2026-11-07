package com.homeassistant.application.usecase.memoryextraction.group

import com.homeassistant.domain.embedding.EmbeddingGeometry
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingKey
import com.homeassistant.application.port.inbound.memoryextraction.ComponentOrganizationOutcome
import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChange
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChangeConflict
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRebuildRequired
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupSnapshot
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRepository
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupTarget
import com.homeassistant.application.port.outbound.memoryextraction.group.ResultingGroup
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.group.*

/**
 * Places one ungrouped Component into a Group, splitting it when the planner says so, and retries on a fresh snapshot up to
 * [maxAttempts] times when a concurrent change wins the write.
 */
class OrganizeComponentIntoGroups(
    private val groups: GroupRepository,
    private val embeddings: PrepareComponentEmbedding,
    private val planner: GroupOrganizationPlanner,
    private val maxAttempts: Int = 3,
) {
    init { require(maxAttempts > 0) }

    /**
     * Places [component] and returns the Groups it changed, or why nothing was written.
     * @throws GroupRebuildRequired when stored Group vectors are outdated or come from another embedding space or dimension.
     */
    fun organize(component: Component): ComponentOrganizationOutcome {
        val initial = groups.findSnapshot()
        validateSpace(initial)
        if (initial.embeddings.containsKey(component.id)) return ComponentOrganizationOutcome.AlreadyOrganized
        val embedding = embeddings.prepare(component)
        repeat(maxAttempts) { attempt ->
            val snapshot = if (attempt == 0) initial else groups.findSnapshot()
            validateSpace(snapshot)
            if (snapshot.embeddings.containsKey(component.id)) return ComponentOrganizationOutcome.AlreadyOrganized
            validateDimension(snapshot, embedding)
            val allEmbeddings = snapshot.embeddings + (component.id to embedding)
            val decision = planner.plan(component.id, snapshot.groups.map { it.group },
                snapshot.groups.associate { it.group.id to it.centroid }, allEmbeddings.mapValues { it.value.vector })
            val change = changeFor(snapshot, embedding, decision)
            runCatching { groups.save(change) }.fold(
                onSuccess = { saved -> return ComponentOrganizationOutcome.Organized(saved.map { it.id }, decision.split) },
                onFailure = { error -> if (error !is GroupChangeConflict) throw error },
            )
        }
        return ComponentOrganizationOutcome.ConflictExhausted
    }

    private fun validateSpace(snapshot: GroupSnapshot) {
        if (snapshot.embeddings.values.any { it.key.space != embeddings.space }) {
            throw GroupRebuildRequired("group embeddings use a different model or input format")
        }
    }

    private fun validateDimension(snapshot: GroupSnapshot, incoming: Embedding<ComponentEmbeddingKey>) {
        if (snapshot.embeddings.values.any { it.vector.dimension != incoming.vector.dimension }) {
            throw GroupRebuildRequired("group embedding dimension changed")
        }
    }

    private fun changeFor(snapshot: GroupSnapshot, incoming: Embedding<ComponentEmbeddingKey>, decision: GroupOrganizationPlan): GroupChange {
        val selected = snapshot.groups.filter { it.group.id in decision.consumedGroupIds }
        val componentIds = decision.partition.parts.flatten().toSet()
        val allEmbeddings = snapshot.embeddings + (incoming.key.componentId to incoming)
        val results = decision.partition.parts.mapIndexed { index, ids ->
            val target = if (index == 0 && selected.isNotEmpty()) GroupTarget.Existing(selected.single().group.id)
                else GroupTarget.Created
            ResultingGroup(target, ids, EmbeddingGeometry.centroid(ids.sorted().map { allEmbeddings.getValue(it).vector }))
        }
        return GroupChange(
            selected.associate { it.group.id to it.revision },
            setOf(incoming.key.componentId), results, allEmbeddings.filterKeys { it in componentIds },
        )
    }
}
