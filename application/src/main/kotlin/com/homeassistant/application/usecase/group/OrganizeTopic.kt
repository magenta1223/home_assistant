package com.homeassistant.application.usecase.group

import com.homeassistant.application.port.input.group.TopicOrganizationOutcome
import com.homeassistant.application.port.output.group.*
import com.homeassistant.domain.group.*
import java.util.UUID

class OrganizeTopic(
    private val snapshots: GroupSnapshotReader,
    private val writer: GroupChangeWriter,
    private val embeddings: PrepareTopicEmbedding,
    private val planner: GroupOrganizationPlanner,
    private val maxAttempts: Int = 3,
) {
    init { require(maxAttempts > 0) }

    fun execute(topic: PendingTopic): TopicOrganizationOutcome {
        val initial = snapshots.snapshot()
        validateSpace(initial)
        if (initial.embeddings.containsKey(topic.id)) return TopicOrganizationOutcome.AlreadyOrganized
        val embedding = embeddings.prepare(topic)
        repeat(maxAttempts) { attempt ->
            val snapshot = if (attempt == 0) initial else snapshots.snapshot()
            validateSpace(snapshot)
            if (snapshot.embeddings.containsKey(topic.id)) return TopicOrganizationOutcome.AlreadyOrganized
            validateDimension(snapshot, embedding)
            val allEmbeddings = snapshot.embeddings + (topic.id to embedding)
            val decision = planner.plan(topic.id, snapshot.groups.map { it.group },
                snapshot.groups.associate { it.group.id to it.centroid }, allEmbeddings.mapValues { it.value.vector })
            val change = changeFor(snapshot, embedding, decision)
            when (val result = writer.apply(change)) {
                is GroupWriteResult.Applied -> return TopicOrganizationOutcome.Organized(result.groupIds, decision.split)
                is GroupWriteResult.AlreadyApplied -> return TopicOrganizationOutcome.Organized(result.groupIds, decision.split)
                GroupWriteResult.Conflict -> Unit
            }
        }
        return TopicOrganizationOutcome.ConflictExhausted
    }

    private fun validateSpace(snapshot: GroupSnapshot) {
        if (snapshot.topicsWithOutdatedGroupVector.isNotEmpty()) throw GroupRebuildRequired("group topic vector changed")
        if (snapshot.embeddings.values.any { it.key.space != embeddings.space }) {
            throw GroupRebuildRequired("group embeddings use a different model or input format")
        }
    }

    private fun validateDimension(snapshot: GroupSnapshot, incoming: TopicEmbedding) {
        if (snapshot.embeddings.values.any { it.vector.dimension != incoming.vector.dimension }) {
            throw GroupRebuildRequired("group embedding dimension changed")
        }
    }

    private fun changeFor(snapshot: GroupSnapshot, incoming: TopicEmbedding, decision: GroupOrganizationPlan): GroupChange {
        val selected = snapshot.groups.filter { it.group.id in decision.consumedGroupIds }
        val topicIds = decision.partition.parts.flatten().toSet()
        val allEmbeddings = snapshot.embeddings + (incoming.key.topicId to incoming)
        val results = decision.partition.parts.mapIndexed { index, ids ->
            val target = if (index == 0 && selected.isNotEmpty()) GroupTarget.Existing(selected.single().group.id)
                else GroupTarget.Created("part-$index")
            ResultingGroup(target, ids, GroupGeometry.centroid(ids.sorted().map { allEmbeddings.getValue(it).vector }))
        }
        return GroupChange(
            UUID.randomUUID().toString(), selected.associate { it.group.id to it.revision },
            setOf(incoming.key.topicId), results, allEmbeddings.filterKeys { it in topicIds },
        )
    }
}
