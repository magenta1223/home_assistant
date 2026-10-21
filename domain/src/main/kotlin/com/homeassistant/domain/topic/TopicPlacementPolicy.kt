package com.homeassistant.domain.topic

import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.TopicVector

sealed interface TopicPlacement {
    data object Create : TopicPlacement
    /** Nearest Topic first. */
    data class Join(val topicIds: List<Int>) : TopicPlacement {
        init { require(topicIds.isNotEmpty() && topicIds.distinct().size == topicIds.size) }
    }
}

/**
 * A memory joins every Topic whose cosine reaches [joinAllThreshold]. When fewer than [fillUpTo] are that close,
 * the nearest Topics reaching [joinThreshold] fill up to [fillUpTo]. With none reaching [joinThreshold] it starts a Topic.
 */
class TopicPlacementPolicy(
    private val joinThreshold: Double = 0.6,
    private val joinAllThreshold: Double = 0.8,
    private val fillUpTo: Int = 3,
) {
    init {
        require(joinThreshold.isFinite() && joinThreshold in -1.0..1.0)
        require(joinAllThreshold.isFinite() && joinAllThreshold in joinThreshold..1.0)
        require(fillUpTo >= 1)
    }

    fun decide(memory: TopicVector, centroids: Map<Int, TopicVector>): TopicPlacement {
        // Sorting by id first keeps the smaller id ahead on equal cosine.
        val near = centroids.entries.sortedBy { it.key }.map { it.key to GroupGeometry.cosine(memory, it.value) }
            .filter { it.second >= joinThreshold }.sortedByDescending { it.second }
        if (near.isEmpty()) return TopicPlacement.Create
        val sure = near.count { it.second >= joinAllThreshold }
        return TopicPlacement.Join(near.take(maxOf(sure, fillUpTo)).map { it.first })
    }
}
