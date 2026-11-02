package com.homeassistant.domain.component

import com.homeassistant.domain.group.ComponentVector

/**
 * Sum of a Component's unit episode vectors. Keeping the sum lets a join add one vector exactly;
 * its direction is the normalized mean, the same as `GroupGeometry.centroid` of the members.
 */
data class ComponentCentroid(val sum: List<Double>, val count: Int) {
    init {
        require(count > 0) { "centroid must cover an episode" }
        require(sum.isNotEmpty() && sum.all { it.isFinite() }) { "invalid centroid coordinates" }
    }

    val direction: ComponentVector get() = ComponentVector.normalized(sum)

    operator fun plus(episode: ComponentVector): ComponentCentroid {
        require(episode.dimension == sum.size) { "vector dimension mismatch" }
        return ComponentCentroid(sum.zip(episode.values(), Double::plus), count + 1)
    }

    companion object {
        fun of(episode: ComponentVector) = ComponentCentroid(episode.values(), 1)
    }
}
