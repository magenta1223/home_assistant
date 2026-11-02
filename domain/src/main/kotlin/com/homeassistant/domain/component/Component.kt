package com.homeassistant.domain.component

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess

/**
 * xMemory component: one centroid over its episodes, which it lists by each episode's access.
 * Membership never changes an episode or its access.
 */
data class Component(
    val id: Int,
    val centroid: ComponentCentroid,
    val slices: Map<EpisodeAccess, List<Int>>,
) {
    init {
        require(id > 0) { "component id must be positive" }
        val episodeIds = slices.values.flatten()
        require(slices.values.all { it.isNotEmpty() }) { "component slice must contain episodes" }
        require(episodeIds.all { it > 0 }) { "episode ids must be positive" }
        require(episodeIds.distinct().size == episodeIds.size) { "an episode belongs to one slice" }
        require(episodeIds.size == centroid.count) { "centroid must cover every episode" }
    }

    /** Episodes in the slices [requester] can read; empty means the Component is hidden from them. */
    fun episodeIdsReadableBy(requester: UserId): List<Int> = viewBy(requester).episodeIds

    /** The slices [requester] can read: PUBLIC and those whose access lists them. */
    fun viewBy(requester: UserId): ComponentView = ComponentView(id, slices.filterKeys { it.isVisibleTo(requester) })
}
