package com.homeassistant.domain.group

sealed interface GroupPlacement {
    data object Create : GroupPlacement
    data class Attach(val groupId: Int) : GroupPlacement
}

class GroupPlacementPolicy(private val settings: GroupPolicySettings) {
    fun decide(topic: TopicVector, centroids: Map<Int, TopicVector>): GroupPlacement {
        val nearest = centroids.entries.sortedBy { it.key }.maxByOrNull { GroupGeometry.cosine(topic, it.value) }
            ?: return GroupPlacement.Create
        return if (GroupGeometry.cosine(topic, nearest.value) > settings.attachThreshold) {
            GroupPlacement.Attach(nearest.key)
        } else GroupPlacement.Create
    }
}
