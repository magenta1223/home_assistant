package com.homeassistant.domain.group

sealed interface GroupPlacement {
    data object Create : GroupPlacement
    data class Attach(val groupId: Int) : GroupPlacement
}

class GroupPlacementPolicy(private val settings: GroupPolicySettings) {
    fun decide(component: ComponentVector, centroids: Map<Int, ComponentVector>): GroupPlacement {
        val nearest = centroids.entries.sortedBy { it.key }.maxByOrNull { GroupGeometry.cosine(component, it.value) }
            ?: return GroupPlacement.Create
        return if (GroupGeometry.cosine(component, nearest.value) > settings.attachThreshold) {
            GroupPlacement.Attach(nearest.key)
        } else GroupPlacement.Create
    }
}
