package com.homeassistant.domain.group

data class GroupOrganizationPlan(
    val consumedGroupIds: Set<Int>,
    val partition: TopicPartition,
    val split: GroupSplit,
)

/** Combines pure policies into one incremental organization decision; no persistence identities are allocated. */
class GroupOrganizationPlanner(
    private val placement: GroupPlacementPolicy,
    private val splitting: GroupSplitPolicy,
) {
    fun plan(
        incomingTopicId: Int,
        groups: List<Group>,
        centroids: Map<Int, TopicVector>,
        vectors: Map<Int, TopicVector>,
    ): GroupOrganizationPlan {
        require(groups.none { incomingTopicId in it.topicIds }) { "incoming topic is already grouped" }
        val consumed = when (val choice = placement.decide(vectors.getValue(incomingTopicId), centroids)) {
            GroupPlacement.Create -> emptySet()
            is GroupPlacement.Attach -> setOf(choice.groupId)
        }
        val target = groups.filter { it.id in consumed }.flatMap { it.topicIds }.toSet() + incomingTopicId
        val surrounding = groups.filter { it.id !in consumed }.map { it.topicIds }
        val split = splitting.decide(target, surrounding, vectors)
        val partition = when (split) {
            GroupSplit.Unchanged -> TopicPartition(listOf(target))
            is GroupSplit.Divided -> split.partition
        }.canonical()
        return GroupOrganizationPlan(consumed, partition, split)
    }
}
