package com.homeassistant.domain.topic

import com.homeassistant.domain.group.TopicVector

/**
 * Sum of a Topic's unit memory vectors. Keeping the sum lets a join add one vector exactly;
 * its direction is the normalized mean, the same as `GroupGeometry.centroid` of the members.
 */
data class TopicCentroid(val sum: List<Double>, val count: Int) {
    init {
        require(count > 0) { "centroid must cover a memory" }
        require(sum.isNotEmpty() && sum.all { it.isFinite() }) { "invalid centroid coordinates" }
    }

    val direction: TopicVector get() = TopicVector.normalized(sum)

    operator fun plus(memory: TopicVector): TopicCentroid {
        require(memory.dimension == sum.size) { "vector dimension mismatch" }
        return TopicCentroid(sum.zip(memory.values(), Double::plus), count + 1)
    }

    companion object {
        fun of(memory: TopicVector) = TopicCentroid(memory.values(), 1)
    }
}
