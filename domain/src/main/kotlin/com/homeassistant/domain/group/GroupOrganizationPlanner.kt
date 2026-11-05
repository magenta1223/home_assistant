package com.homeassistant.domain.group

data class GroupOrganizationPlan(
    val consumedGroupIds: Set<Int>,
    val partition: ComponentPartition,
    val split: GroupSplit,
)

/** Combines pure policies into one incremental organization decision; no persistence identities are allocated. */
class GroupOrganizationPlanner(
    private val placement: GroupPlacementPolicy,
    private val splitting: GroupSplitPolicy,
) {
    fun plan(
        incomingComponentId: Int,
        groups: List<Group>,
        centroids: Map<Int, ComponentVector>,
        vectors: Map<Int, ComponentVector>,
    ): GroupOrganizationPlan {
        require(groups.none { incomingComponentId in it.componentIds }) { "incoming component is already grouped" }
        val consumed = when (val choice = placement.decide(vectors.getValue(incomingComponentId), centroids)) {
            GroupPlacement.Create -> emptySet()
            is GroupPlacement.Attach -> setOf(choice.groupId)
        }
        val target = groups.filter { it.id in consumed }.flatMap { it.componentIds }.toSet() + incomingComponentId
        val surrounding = groups.filter { it.id !in consumed }.map { it.componentIds }
        val split = splitting.decide(target, surrounding, vectors)
        val partition = when (split) {
            GroupSplit.Unchanged -> ComponentPartition(listOf(target))
            is GroupSplit.Divided -> split.partition
        }.canonical()
        return GroupOrganizationPlan(consumed, partition, split)
    }
}
