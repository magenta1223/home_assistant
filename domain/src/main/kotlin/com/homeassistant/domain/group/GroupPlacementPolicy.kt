package com.homeassistant.domain.group

import com.homeassistant.domain.embedding.EmbeddingVector

sealed interface GroupPlacement {
    data object Create : GroupPlacement
    data class Attach(val groupId: Int) : GroupPlacement
}

class GroupPlacementPolicy(private val settings: GroupPolicySettings) {
    fun decide(component: EmbeddingVector, centroids: Map<Int, EmbeddingVector>): GroupPlacement {
        val nearest = centroids.entries.sortedBy { it.key }.maxByOrNull { GroupGeometry.cosine(component, it.value) }
            ?: return GroupPlacement.Create
        return if (GroupGeometry.cosine(component, nearest.value) > settings.attachThreshold) {
            GroupPlacement.Attach(nearest.key)
        } else GroupPlacement.Create
    }
}
