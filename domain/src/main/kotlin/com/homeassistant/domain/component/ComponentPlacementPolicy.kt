package com.homeassistant.domain.component

import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.ComponentVector

sealed interface ComponentPlacement {
    data object Create : ComponentPlacement
    /** Nearest Component first. */
    data class Join(val componentIds: List<Int>) : ComponentPlacement {
        init { require(componentIds.isNotEmpty() && componentIds.distinct().size == componentIds.size) }
    }
}

/**
 * An episode joins every Component whose cosine reaches [joinAllThreshold]. When fewer than [fillUpTo] are that close,
 * the nearest Components reaching [joinThreshold] fill up to [fillUpTo]. With none reaching [joinThreshold] it starts a Component.
 */
class ComponentPlacementPolicy(
    private val joinThreshold: Double = 0.6,
    private val joinAllThreshold: Double = 0.8,
    private val fillUpTo: Int = 3,
) {
    init {
        require(joinThreshold.isFinite() && joinThreshold in -1.0..1.0)
        require(joinAllThreshold.isFinite() && joinAllThreshold in joinThreshold..1.0)
        require(fillUpTo >= 1)
    }

    fun decide(episode: ComponentVector, centroids: Map<Int, ComponentVector>): ComponentPlacement {
        // Sorting by id first keeps the smaller id ahead on equal cosine.
        val near = centroids.entries.sortedBy { it.key }.map { it.key to GroupGeometry.cosine(episode, it.value) }
            .filter { it.second >= joinThreshold }.sortedByDescending { it.second }
        if (near.isEmpty()) return ComponentPlacement.Create
        val sure = near.count { it.second >= joinAllThreshold }
        return ComponentPlacement.Join(near.take(maxOf(sure, fillUpTo)).map { it.first })
    }
}
