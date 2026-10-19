package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.topic.MemoryOrganizationOutcome
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.GroupOrganizationPlan
import com.homeassistant.domain.group.GroupOrganizationPlanner

/** Places one memory into the nearest organized Topic or a new one, splitting an oversized Topic in the same change. */
class OrganizeMemory(
    private val snapshots: TopicOrganizationSnapshotReader,
    private val writer: TopicOrganizationWriter,
    private val embeddings: PrepareMemoryEmbedding,
    private val planner: GroupOrganizationPlanner,
    private val maxAttempts: Int = 3,
) {
    init { require(maxAttempts > 0) }

    fun execute(memory: PendingMemory): MemoryOrganizationOutcome {
        val initial = snapshots.snapshot()
        validateSpace(initial)
        if (initial.embeddings.containsKey(memory.id)) return MemoryOrganizationOutcome.AlreadyOrganized
        val embedding = embeddings.prepare(memory)
        repeat(maxAttempts) { attempt ->
            val snapshot = if (attempt == 0) initial else snapshots.snapshot()
            validateSpace(snapshot)
            if (snapshot.embeddings.containsKey(memory.id)) return MemoryOrganizationOutcome.AlreadyOrganized
            validateDimension(snapshot, embedding)
            val allEmbeddings = snapshot.embeddings + (memory.id to embedding)
            val decision = planner.plan(memory.id, snapshot.topics.associate { it.topicId to it.memoryIds },
                snapshot.topics.associate { it.topicId to it.centroid }, allEmbeddings.mapValues { it.value.vector })
            when (val result = writer.apply(changeFor(snapshot, memory, allEmbeddings, decision))) {
                is TopicOrganizationWriteResult.Applied -> return MemoryOrganizationOutcome.Organized(result.topicIds, decision.split)
                TopicOrganizationWriteResult.Conflict -> Unit
            }
        }
        return MemoryOrganizationOutcome.ConflictExhausted
    }

    private fun validateSpace(snapshot: TopicOrganizationSnapshot) {
        if (snapshot.embeddings.values.any { it.key.space != embeddings.space }) {
            throw TopicRebuildRequired("topic memory embeddings use a different model or input format")
        }
    }

    private fun validateDimension(snapshot: TopicOrganizationSnapshot, incoming: MemoryEmbedding) {
        if (snapshot.embeddings.values.any { it.vector.dimension != incoming.vector.dimension }) {
            throw TopicRebuildRequired("topic memory embedding dimension changed")
        }
    }

    /** The part holding the smallest memory keeps the attached Topic; other parts become new Topics. */
    private fun changeFor(
        snapshot: TopicOrganizationSnapshot,
        incoming: PendingMemory,
        allEmbeddings: Map<Int, MemoryEmbedding>,
        decision: GroupOrganizationPlan,
    ): TopicOrganizationChange {
        val selected = snapshot.topics.filter { it.topicId in decision.consumedGroupIds }
        val memoryIds = decision.partition.parts.flatten().toSet()
        val results = decision.partition.parts.mapIndexed { index, ids ->
            val target = if (index == 0 && selected.isNotEmpty()) TopicTarget.Existing(selected.single().topicId)
                else TopicTarget.Created
            ResultingTopic(target, ids, GroupGeometry.centroid(ids.sorted().map { allEmbeddings.getValue(it).vector }))
        }
        return TopicOrganizationChange(
            selected.associate { it.topicId to it.revision }, setOf(incoming.id), results,
            allEmbeddings.filterKeys { it in memoryIds },
        )
    }
}
