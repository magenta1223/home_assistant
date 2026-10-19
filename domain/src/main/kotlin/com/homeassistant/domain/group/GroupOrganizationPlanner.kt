package com.homeassistant.domain.group

data class GroupOrganizationPlan(
    val consumedGroupIds: Set<Int>,
    val partition: TopicPartition,
    val split: GroupSplit,
)

/**
 * Combines pure policies into one incremental organization decision; no persistence identities are allocated.
 * The same xMemory placement and split rules organize Topics into Groups and Memories into Topics,
 * so clusters are plain member ID sets keyed by cluster ID.
 */
class GroupOrganizationPlanner(
    private val placement: GroupPlacementPolicy,
    private val splitting: GroupSplitPolicy,
) {
    fun plan(
        incomingId: Int,
        clusters: Map<Int, Set<Int>>,
        centroids: Map<Int, TopicVector>,
        vectors: Map<Int, TopicVector>,
    ): GroupOrganizationPlan {
        require(clusters.values.none { incomingId in it }) { "incoming member is already organized" }
        val consumed = when (val choice = placement.decide(vectors.getValue(incomingId), centroids)) {
            GroupPlacement.Create -> emptySet()
            is GroupPlacement.Attach -> setOf(choice.groupId)
        }
        val target = consumed.flatMap { clusters.getValue(it) }.toSet() + incomingId
        val surrounding = clusters.filterKeys { it !in consumed }.values.toList()
        val split = splitting.decide(target, surrounding, vectors)
        val partition = when (split) {
            GroupSplit.Unchanged -> TopicPartition(listOf(target))
            is GroupSplit.Divided -> split.partition
        }.canonical()
        return GroupOrganizationPlan(consumed, partition, split)
    }
}
