package com.homeassistant.domain.component

import com.homeassistant.domain.episode.EpisodeAccess

/**
 * The part of a Component one viewer can read: the PUBLIC slice and the slices that list the viewer.
 * Viewers who read the same slices share a view. A slice only gains episodes, so its size is its version.
 */
data class ComponentView(val componentId: Int, val slices: Map<EpisodeAccess, List<Int>>) {
    init {
        require(componentId > 0) { "component id must be positive" }
        require(slices.values.all { it.isNotEmpty() }) { "component slice must contain episodes" }
    }

    /** True when the viewer can read none of the Component. */
    val isHidden: Boolean get() = slices.isEmpty()

    val key: ComponentViewKey get() = ComponentViewKey(componentId, slices.keys)

    val versions: Map<EpisodeAccess, Int> get() = slices.mapValues { it.value.size }

    val episodeIds: List<Int> get() = slices.values.flatten()
}

/** A Component seen through one set of slices. A permission change changes the set, so it never reuses another view's data. */
data class ComponentViewKey(val componentId: Int, val slices: Set<EpisodeAccess>) {
    init { require(componentId > 0 && slices.isNotEmpty()) }
}
