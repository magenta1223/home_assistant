package com.homeassistant.domain.topic

import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.TopicVector

sealed interface TopicPlacement {
    data object Create : TopicPlacement
    data class Join(val topicId: Int) : TopicPlacement
}

/** A memory joins the nearest Topic when the cosine reaches [joinThreshold]; otherwise it starts a Topic. */
class TopicPlacementPolicy(private val joinThreshold: Double = 0.6) {
    init { require(joinThreshold.isFinite() && joinThreshold in -1.0..1.0) }

    fun decide(memory: TopicVector, centroids: Map<Int, TopicVector>): TopicPlacement {
        val nearest = centroids.entries.sortedBy { it.key }.maxByOrNull { GroupGeometry.cosine(memory, it.value) }
            ?: return TopicPlacement.Create
        return if (GroupGeometry.cosine(memory, nearest.value) >= joinThreshold) {
            TopicPlacement.Join(nearest.key)
        } else TopicPlacement.Create
    }
}
